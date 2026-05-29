package com.symptomgraph.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.symptomgraph.dto.CorpusRecordResponse;
import com.symptomgraph.dto.CorpusUploadResponse;
import com.symptomgraph.dto.GeminiRecognitionItem;
import com.symptomgraph.dto.GeminiRecognitionResult;
import com.symptomgraph.dto.OssUploadResult;
import com.symptomgraph.entity.CorpusRecord;
import com.symptomgraph.exception.GeminiRecognitionException;
import com.symptomgraph.service.CorpusIngestionService;
import com.symptomgraph.service.CorpusRecordService;
import com.symptomgraph.service.GeminiVisionService;
import com.symptomgraph.service.MarkdownExportService;
import com.symptomgraph.service.OssStorageService;
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
    private static final DateTimeFormatter CAPTURE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CorpusRecordService corpusRecordService;
    private final OssStorageService ossStorageService;
    private final GeminiVisionService geminiVisionService;
    private final MarkdownExportService markdownExportService;
    private final ObjectMapper objectMapper;

    public CorpusIngestionServiceImpl(CorpusRecordService corpusRecordService,
                                      OssStorageService ossStorageService,
                                      GeminiVisionService geminiVisionService,
                                      MarkdownExportService markdownExportService,
                                      ObjectMapper objectMapper) {
        this.corpusRecordService = corpusRecordService;
        this.ossStorageService = ossStorageService;
        this.geminiVisionService = geminiVisionService;
        this.markdownExportService = markdownExportService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public CorpusUploadResponse ingest(MultipartFile file, boolean force) {
        validateFile(file);
        byte[] imageBytes = readBytes(file);
        String imageHash = ImageHashUtils.sha256Hex(imageBytes);
        List<CorpusRecord> existingRecords = corpusRecordService.listByImageHash(imageHash);

        if (!force && !existingRecords.isEmpty()) {
            return buildResponse(existingRecords, imageHash, true, false);
        }

        String captureId = existingRecords.isEmpty() ? generateCaptureId() : existingRecords.get(0).getCaptureId();
        String ossBucket;
        String ossObjectKey;
        if (existingRecords.isEmpty()) {
            OssUploadResult uploadResult = ossStorageService.upload(file, captureId);
            ossBucket = uploadResult.getBucket();
            ossObjectKey = uploadResult.getObjectKey();
        } else {
            ossBucket = existingRecords.get(0).getOssBucket();
            ossObjectKey = existingRecords.get(0).getOssObjectKey();
            corpusRecordService.removeByImageHash(imageHash);
        }

        List<CorpusRecord> records = recognizeAndBuildRecords(imageBytes, file.getContentType(), captureId, imageHash, ossBucket, ossObjectKey);
        corpusRecordService.saveBatch(records);
        for (CorpusRecord record : records) {
            record.setMarkdownPath(markdownExportService.export(record));
        }
        corpusRecordService.updateBatchById(records);

        return buildResponse(records, imageHash, false, force);
    }

    private List<CorpusRecord> recognizeAndBuildRecords(byte[] imageBytes,
                                                        String mimeType,
                                                        String captureId,
                                                        String imageHash,
                                                        String ossBucket,
                                                        String ossObjectKey) {
        try {
            GeminiRecognitionResult recognitionResult = geminiVisionService.recognize(imageBytes, mimeType);
            return buildSuccessRecords(recognitionResult, captureId, imageHash, ossBucket, ossObjectKey);
        } catch (GeminiRecognitionException ex) {
            CorpusRecord failedRecord = baseRecord(captureId, 1, imageHash, ossBucket, ossObjectKey);
            failedRecord.setParseStatus(ex.getParseStatus());
            failedRecord.setErrorMessage(ex.getMessage());
            failedRecord.setModelRawResponse(ex.getModelRawResponse());
            failedRecord.setTags("[]");
            return List.of(failedRecord);
        }
    }

    private List<CorpusRecord> buildSuccessRecords(GeminiRecognitionResult result,
                                                   String captureId,
                                                   String imageHash,
                                                   String ossBucket,
                                                   String ossObjectKey) {
        List<GeminiRecognitionItem> items = result.getItems() == null ? List.of() : result.getItems();
        if (items.isEmpty()) {
            CorpusRecord record = baseRecord(captureId, 1, imageHash, ossBucket, ossObjectKey);
            applyRecognitionResult(record, result);
            record.setTags("[]");
            return List.of(record);
        }

        List<CorpusRecord> records = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            GeminiRecognitionItem item = items.get(i);
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

    private void applyRecognitionResult(CorpusRecord record, GeminiRecognitionResult result) {
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
        }
        response.setRecords(records.stream()
                .map(record -> CorpusRecordResponse.from(record, parseTags(record.getTags())))
                .toList());
        return response;
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
