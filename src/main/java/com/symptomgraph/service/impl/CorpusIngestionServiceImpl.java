package com.symptomgraph.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.symptomgraph.dto.CorpusProcessMessage;
import com.symptomgraph.dto.CorpusRecordResponse;
import com.symptomgraph.dto.CorpusUploadResponse;
import com.symptomgraph.dto.OssUploadResult;
import com.symptomgraph.dto.VisionRecognitionItem;
import com.symptomgraph.dto.VisionRecognitionResult;
import com.symptomgraph.entity.CorpusRecord;
import com.symptomgraph.exception.VisionRecognitionException;
import com.symptomgraph.mq.CorpusProcessMessageProducer;
import com.symptomgraph.service.CorpusIngestionService;
import com.symptomgraph.service.CorpusRecordService;
import com.symptomgraph.service.ImageHashBloomFilterService;
import com.symptomgraph.service.MarkdownExportService;
import com.symptomgraph.service.OssStorageService;
import com.symptomgraph.service.VisionRecognitionService;
import com.symptomgraph.util.ImageHashUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

@Service
public class CorpusIngestionServiceImpl implements CorpusIngestionService {

    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_EMPTY_RESULT = "EMPTY_RESULT";
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final DateTimeFormatter CAPTURE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CorpusRecordService corpusRecordService;
    private final ImageHashBloomFilterService imageHashBloomFilterService;
    private final OssStorageService ossStorageService;
    private final VisionRecognitionService visionRecognitionService;
    private final MarkdownExportService markdownExportService;
    private final CorpusProcessMessageProducer corpusProcessMessageProducer;
    private final ObjectMapper objectMapper;

    // 构造器
    public CorpusIngestionServiceImpl(CorpusRecordService corpusRecordService,
                                      ImageHashBloomFilterService imageHashBloomFilterService,
                                      OssStorageService ossStorageService,
                                      VisionRecognitionService visionRecognitionService,
                                      MarkdownExportService markdownExportService,
                                      CorpusProcessMessageProducer corpusProcessMessageProducer,
                                      ObjectMapper objectMapper) {
        this.corpusRecordService = corpusRecordService;
        this.imageHashBloomFilterService = imageHashBloomFilterService;
        this.ossStorageService = ossStorageService;
        this.visionRecognitionService = visionRecognitionService;
        this.markdownExportService = markdownExportService;
        this.corpusProcessMessageProducer = corpusProcessMessageProducer;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public CorpusUploadResponse ingest(MultipartFile file, boolean force) {
        validateFile(file);
        byte[] imageBytes = readBytes(file);
        String imageHash = ImageHashUtils.sha256Hex(imageBytes);
        List<CorpusRecord> existingRecords = findExistingRecords(imageHash);

        // 若已经存在记录，且不要求强行识别，则返回已有结果
        if (!force && !existingRecords.isEmpty()) {
            return buildResponse(existingRecords, imageHash, true, false);
        }

        if (existingRecords.isEmpty()) {
            return ingestNewImageAsync(file, imageHash, force);
        }

        String captureId = existingRecords.get(0).getCaptureId();
        String ossBucket;
        String ossObjectKey;
        boolean replacingExistingRecords = force;
        ossBucket = existingRecords.get(0).getOssBucket();
        ossObjectKey = existingRecords.get(0).getOssObjectKey();

        List<CorpusRecord> records = recognizeAndBuildRecords(imageBytes, file.getContentType(), captureId, imageHash, ossBucket, ossObjectKey);
        if (replacingExistingRecords && !isSuccessfulRecognition(records)) {
            return buildResponse(records, imageHash, false, true);
        }
        if (replacingExistingRecords) {
            corpusRecordService.removeByImageHash(imageHash);
        }
        corpusRecordService.saveBatch(records);
        imageHashBloomFilterService.add(imageHash);
        for (CorpusRecord record : records) {
            if (STATUS_SUCCESS.equals(record.getParseStatus())) {
                record.setMarkdownPath(markdownExportService.export(record));
            }
        }
        corpusRecordService.updateBatchById(records);

        return buildResponse(records, imageHash, false, force);
    }

    private CorpusUploadResponse ingestNewImageAsync(MultipartFile file, String imageHash, boolean force) {
        String captureId = generateCaptureId();

        // 核心网络操作说明：新图只在上传请求线程中完成 OSS 原图存储，不再同步调用大模型。
        // 大模型识别属于高延迟外部网络调用，会在后续 RabbitMQ Consumer 中异步执行，从而降低上传接口 RT。
        OssUploadResult uploadResult = ossStorageService.upload(file, captureId);

        CorpusRecord processingRecord = buildProcessingRecord(
                captureId,
                imageHash,
                uploadResult.getBucket(),
                uploadResult.getObjectKey()
        );

        // 数据库状态流转说明：这里先写入一条 comment_index=1 的 PROCESSING 占位记录。
        // 当前项目仍使用 corpus_record 单表表达一图多评论；Consumer 成功后会复用这条记录写入第一条评论，
        // 并为第二条及后续评论追加新记录。投递失败会随事务回滚，避免留下没有后台任务处理的悬挂记录。
        corpusRecordService.save(processingRecord);
        imageHashBloomFilterService.add(imageHash);

        CorpusProcessMessage message = buildProcessMessage(processingRecord, file.getContentType(), force);
        corpusProcessMessageProducer.send(message);

        CorpusUploadResponse response = buildResponse(List.of(processingRecord), imageHash, false, force);
        response.setAsyncSubmitted(true);
        return response;
    }

    private List<CorpusRecord> findExistingRecords(String imageHash) {
        if (!imageHashBloomFilterService.mightContain(imageHash)) {
            return List.of();
        }
        return corpusRecordService.listByImageHash(imageHash);
    }

    private CorpusRecord buildProcessingRecord(String captureId, String imageHash, String ossBucket, String ossObjectKey) {
        CorpusRecord record = baseRecord(captureId, 1, imageHash, ossBucket, ossObjectKey);
        record.setParseStatus(STATUS_PROCESSING);
        record.setTags("[]");
        return record;
    }

    private CorpusProcessMessage buildProcessMessage(CorpusRecord record, String mimeType, boolean force) {
        CorpusProcessMessage message = new CorpusProcessMessage();
        message.setRecordId(record.getId());
        message.setCaptureId(record.getCaptureId());
        message.setImageHash(record.getImageHash());
        message.setOssBucket(record.getOssBucket());
        message.setOssObjectKey(record.getOssObjectKey());
        message.setMimeType(mimeType);
        message.setForce(force);
        return message;
    }

    private List<CorpusRecord> recognizeAndBuildRecords(byte[] imageBytes,
                                                        String mimeType,
                                                        String captureId,
                                                        String imageHash,
                                                        String ossBucket,
                                                        String ossObjectKey) {
        try {
            VisionRecognitionResult recognitionResult = visionRecognitionService.recognize(imageBytes, mimeType);
            return buildSuccessRecords(recognitionResult, captureId, imageHash, ossBucket, ossObjectKey);
        } catch (VisionRecognitionException ex) {
            CorpusRecord failedRecord = baseRecord(captureId, 1, imageHash, ossBucket, ossObjectKey);
            failedRecord.setParseStatus(ex.getParseStatus());
            failedRecord.setErrorMessage(ex.getMessage());
            failedRecord.setModelRawResponse(ex.getModelRawResponse());
            failedRecord.setTags("[]");
            return List.of(failedRecord);
        }
    }

    private List<CorpusRecord> buildSuccessRecords(VisionRecognitionResult result,
                                                   String captureId,
                                                   String imageHash,
                                                   String ossBucket,
                                                   String ossObjectKey) {
        List<VisionRecognitionItem> items = result.getItems() == null ? List.of() : result.getItems();
        if (items.isEmpty()) {
            CorpusRecord record = baseRecord(captureId, 1, imageHash, ossBucket, ossObjectKey);
            applyRecognitionResult(record, result);
            record.setTags("[]");
            record.setParseStatus(STATUS_EMPTY_RESULT);
            record.setErrorMessage("Vision recognition returned no visible comment items");
            return List.of(record);
        }

        List<CorpusRecord> records = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            VisionRecognitionItem item = items.get(i);
            Integer commentIndex = item.getCommentIndex() == null ? i + 1 : item.getCommentIndex();
            CorpusRecord record = baseRecord(captureId, commentIndex, imageHash, ossBucket, ossObjectKey);
            applyRecognitionResult(record, result);
            record.setRawContent(item.getRawContent());
            record.setTags(toJson(sanitizeTags(item.getTags())));
            records.add(record);
        }
        return records;
    }

    private CorpusRecord baseRecord(String captureId, Integer commentIndex, String imageHash, String ossBucket, String ossObjectKey) {
        LocalDateTime now = LocalDateTime.now();
        CorpusRecord record = new CorpusRecord();
        record.setCaptureId(captureId);
        record.setCommentIndex(commentIndex);
        record.setCollectedTime(now);
        record.setOssBucket(ossBucket);
        record.setOssObjectKey(ossObjectKey);
        record.setImageHash(imageHash);
        record.setParseStatus(STATUS_SUCCESS);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        return record;
    }

    private void applyRecognitionResult(CorpusRecord record, VisionRecognitionResult result) {
        record.setPlatform(result.getPlatform());
        record.setContextTarget(result.getContextTarget());
        record.setOriginalPublishTime(parseOriginalPublishTime(result.getOriginalPublishTime()));
        record.setModelRawResponse(result.getModelRawResponse());
        record.setParseStatus(STATUS_SUCCESS);
    }

    private CorpusUploadResponse buildResponse(List<CorpusRecord> records, String imageHash, boolean duplicate, boolean force) {
        CorpusUploadResponse response = new CorpusUploadResponse();
        response.setImageHash(imageHash);
        response.setDuplicate(duplicate);
        response.setForce(force);
        if (!records.isEmpty()) {
            response.setCaptureId(records.get(0).getCaptureId());
            response.setRecordId(records.get(0).getId());
            response.setParseStatus(records.get(0).getParseStatus());
        }
        response.setRecords(records.stream()
                .map(record -> CorpusRecordResponse.from(record, parseTags(record.getTags())))
                .toList());
        return response;
    }

    private boolean isSuccessfulRecognition(List<CorpusRecord> records) {
        return records.stream().allMatch(record -> STATUS_SUCCESS.equals(record.getParseStatus()));
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file must not be empty");
        }
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read uploaded file", ex);
        }
    }

    private String generateCaptureId() {
        String timestamp = LocalDateTime.now().format(CAPTURE_TIME_FORMATTER);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return timestamp + "_" + suffix;
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize tags", ex);
        }
    }

    private List<String> sanitizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> sanitizedTags = new LinkedHashSet<>();
        for (String tag : tags) {
            if (!StringUtils.hasText(tag)) {
                continue;
            }
            String sanitizedTag = tag.trim();
            while (sanitizedTag.startsWith("#") || sanitizedTag.startsWith("＃")) {
                sanitizedTag = sanitizedTag.substring(1).trim();
            }
            if (StringUtils.hasText(sanitizedTag)) {
                sanitizedTags.add(sanitizedTag);
            }
        }
        return List.copyOf(sanitizedTags);
    }

    private List<String> parseTags(String tagsJson) {
        if (!StringUtils.hasText(tagsJson)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(tagsJson, new TypeReference<>() {
            });
        } catch (IOException ex) {
            return List.of();
        }
    }

    private LocalDateTime parseOriginalPublishTime(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(value, DATE_TIME_FORMATTER);
            } catch (DateTimeParseException ignoredAgain) {
                return null;
            }
        }
    }
}
