package com.symptomgraph.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.symptomgraph.config.GeminiProperties;
import com.symptomgraph.config.OpenRouterProperties;
import com.symptomgraph.config.VisionProperties;
import com.symptomgraph.dto.CorpusProcessMessage;
import com.symptomgraph.dto.CorpusRecordResponse;
import com.symptomgraph.dto.CorpusUploadResponse;
import com.symptomgraph.dto.OssUploadResult;
import com.symptomgraph.dto.VisionRecognitionItem;
import com.symptomgraph.dto.VisionRecognitionOptions;
import com.symptomgraph.dto.VisionRecognitionResult;
import com.symptomgraph.entity.CaptureRecord;
import com.symptomgraph.entity.CorpusRecord;
import com.symptomgraph.entity.RecognitionRun;
import com.symptomgraph.exception.VisionRecognitionException;
import com.symptomgraph.mq.CorpusProcessMessageProducer;
import com.symptomgraph.service.CaptureRecordService;
import com.symptomgraph.service.CorpusIngestionService;
import com.symptomgraph.service.CorpusRecordService;
import com.symptomgraph.service.ImageHashBloomFilterService;
import com.symptomgraph.service.MarkdownExportService;
import com.symptomgraph.service.OssStorageService;
import com.symptomgraph.service.RecognitionRunService;
import com.symptomgraph.service.VisionRecognitionService;
import com.symptomgraph.util.ImageHashUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
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

    private final CaptureRecordService captureRecordService;
    private final CorpusRecordService corpusRecordService;
    private final ImageHashBloomFilterService imageHashBloomFilterService;
    private final OssStorageService ossStorageService;
    private final VisionRecognitionService visionRecognitionService;
    private final RecognitionRunService recognitionRunService;
    private final MarkdownExportService markdownExportService;
    private final CorpusProcessMessageProducer corpusProcessMessageProducer;
    private final VisionProperties visionProperties;
    private final GeminiProperties geminiProperties;
    private final OpenRouterProperties openRouterProperties;
    private final ObjectMapper objectMapper;

    // 构造器
    public CorpusIngestionServiceImpl(CaptureRecordService captureRecordService,
                                      CorpusRecordService corpusRecordService,
                                      ImageHashBloomFilterService imageHashBloomFilterService,
                                      OssStorageService ossStorageService,
                                      VisionRecognitionService visionRecognitionService,
                                      RecognitionRunService recognitionRunService,
                                      MarkdownExportService markdownExportService,
                                      CorpusProcessMessageProducer corpusProcessMessageProducer,
                                      VisionProperties visionProperties,
                                      GeminiProperties geminiProperties,
                                      OpenRouterProperties openRouterProperties,
                                      ObjectMapper objectMapper) {
        this.captureRecordService = captureRecordService;
        this.corpusRecordService = corpusRecordService;
        this.imageHashBloomFilterService = imageHashBloomFilterService;
        this.ossStorageService = ossStorageService;
        this.visionRecognitionService = visionRecognitionService;
        this.recognitionRunService = recognitionRunService;
        this.markdownExportService = markdownExportService;
        this.corpusProcessMessageProducer = corpusProcessMessageProducer;
        this.visionProperties = visionProperties;
        this.geminiProperties = geminiProperties;
        this.openRouterProperties = openRouterProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public CorpusUploadResponse ingest(MultipartFile file, boolean force) {
        return ingest(file, force, null, null);
    }

    @Override
    @Transactional
    public CorpusUploadResponse ingest(MultipartFile file, boolean force, String provider, String model) {
        validateFile(file);
        byte[] imageBytes = readBytes(file);
        String imageHash = ImageHashUtils.sha256Hex(imageBytes);
        String requestedProvider = resolveRequestedProvider(provider);
        String requestedModel = resolveRequestedModel(requestedProvider, model);
        List<CorpusRecord> existingCorpusRecords = findExistingCorpusRecords(imageHash);

        // 已有语料且不强制重识别时，直接返回历史结果，避免重复调用 OSS 和模型。
        if (!force && !existingCorpusRecords.isEmpty()) {
            return buildResponse(existingCorpusRecords, imageHash, true, false);
        }

        if (existingCorpusRecords.isEmpty()) {
            return ingestNewImageAsync(file, imageHash, force, requestedProvider, requestedModel);
        }

        // force=true 复用历史 OSS 对象，但仍同步重识别，保证“识别成功后才覆盖旧语料”的安全语义。
        String captureBatchId = existingCorpusRecords.get(0).getCaptureId();
        String ossBucket;
        String ossObjectKey;
        boolean replacingExistingRecords = force;
        ossBucket = existingCorpusRecords.get(0).getOssBucket();
        ossObjectKey = existingCorpusRecords.get(0).getOssObjectKey();

        List<CorpusRecord> recognizedCorpusRecords = recognizeAndBuildCorpusRecords(
                imageBytes, file.getContentType(), captureBatchId, imageHash, ossBucket, ossObjectKey,
                requestedProvider, requestedModel);
        if (replacingExistingRecords && !isSuccessfulRecognition(recognizedCorpusRecords)) {
            return buildResponse(recognizedCorpusRecords, imageHash, false, true);
        }
        if (replacingExistingRecords) {
            corpusRecordService.removeByImageHash(imageHash);
        }
        corpusRecordService.saveBatch(recognizedCorpusRecords);
        imageHashBloomFilterService.add(imageHash);
        for (CorpusRecord corpusRecord : recognizedCorpusRecords) {
            if (STATUS_SUCCESS.equals(corpusRecord.getParseStatus())) {
                corpusRecord.setMarkdownPath(markdownExportService.export(corpusRecord));
            }
        }
        corpusRecordService.updateBatchById(recognizedCorpusRecords);

        CorpusUploadResponse response = buildResponse(recognizedCorpusRecords, imageHash, false, force);
        response.setProvider(requestedProvider);
        response.setModel(requestedModel);
        return response;
    }

    private CorpusUploadResponse ingestNewImageAsync(MultipartFile file,
                                                     String imageHash,
                                                     boolean force,
                                                     String provider,
                                                     String model) {
        String captureBatchId = generateCaptureBatchId();

        // 核心网络操作说明：新图只在上传请求线程中完成 OSS 原图存储，不再同步调用大模型。
        // 大模型识别属于高延迟外部网络调用，会在后续 RabbitMQ Consumer 中异步执行，从而降低上传接口 RT。
        OssUploadResult uploadResult = ossStorageService.upload(file, captureBatchId);

        CaptureRecord captureTask = buildCaptureTask(captureBatchId, imageHash, uploadResult, file.getContentType(), force, provider, model);

        captureRecordService.save(captureTask);
        imageHashBloomFilterService.add(imageHash);

        CorpusProcessMessage processMessage = buildProcessMessage(captureTask, file.getContentType(), force);
        corpusProcessMessageProducer.send(processMessage);

        CorpusUploadResponse response = buildAsyncResponse(captureTask, imageHash, force);
        response.setCaptureRecordId(captureTask.getId());
        response.setAsyncSubmitted(true);
        return response;
    }

    private List<CorpusRecord> findExistingCorpusRecords(String imageHash) {
        if (!imageHashBloomFilterService.mightContain(imageHash)) {
            return List.of();
        }
        return corpusRecordService.listByImageHash(imageHash);
    }

    private CaptureRecord buildCaptureTask(String captureBatchId,
                                           String imageHash,
                                           OssUploadResult uploadResult,
                                           String mimeType,
                                           boolean force,
                                           String provider,
                                           String model) {
        LocalDateTime now = LocalDateTime.now();
        CaptureRecord captureTask = new CaptureRecord();
        captureTask.setCaptureId(captureBatchId);
        captureTask.setImageHash(imageHash);
        captureTask.setOssBucket(uploadResult.getBucket());
        captureTask.setOssObjectKey(uploadResult.getObjectKey());
        captureTask.setMimeType(mimeType);
        captureTask.setProvider(provider);
        captureTask.setModel(model);
        captureTask.setProcessStatus(STATUS_PROCESSING);
        captureTask.setRetryCount(0);
        captureTask.setDuplicate(false);
        captureTask.setForce(force);
        captureTask.setCreatedAt(now);
        captureTask.setUpdatedAt(now);
        return captureTask;
    }

    private String resolveCurrentModel() {
        if ("openrouter".equalsIgnoreCase(visionProperties.getProvider())) {
            return openRouterProperties.getModel();
        }
        return geminiProperties.getModel();
    }

    private String resolveRequestedProvider(String provider) {
        if (StringUtils.hasText(provider)) {
            return provider.trim();
        }
        return visionProperties.getProvider();
    }

    private String resolveRequestedModel(String provider, String model) {
        if (StringUtils.hasText(model)) {
            return model.trim();
        }
        if ("openrouter".equalsIgnoreCase(provider)) {
            return openRouterProperties.getModel();
        }
        if ("gemini".equalsIgnoreCase(provider)) {
            return geminiProperties.getModel();
        }
        return resolveCurrentModel();
    }

    private CorpusProcessMessage buildProcessMessage(CaptureRecord captureTask, String mimeType, boolean force) {
        CorpusProcessMessage processMessage = new CorpusProcessMessage();
        processMessage.setCaptureRecordId(captureTask.getId());
        processMessage.setCaptureId(captureTask.getCaptureId());
        processMessage.setImageHash(captureTask.getImageHash());
        processMessage.setOssBucket(captureTask.getOssBucket());
        processMessage.setOssObjectKey(captureTask.getOssObjectKey());
        processMessage.setMimeType(mimeType);
        processMessage.setProvider(captureTask.getProvider());
        processMessage.setModel(captureTask.getModel());
        processMessage.setForce(force);
        return processMessage;
    }

    private CorpusUploadResponse buildAsyncResponse(CaptureRecord captureTask, String imageHash, boolean force) {
        CorpusUploadResponse response = new CorpusUploadResponse();
        response.setCaptureId(captureTask.getCaptureId());
        response.setImageHash(imageHash);
        response.setCaptureRecordId(captureTask.getId());
        response.setParseStatus(captureTask.getProcessStatus());
        response.setProvider(captureTask.getProvider());
        response.setModel(captureTask.getModel());
        response.setDuplicate(false);
        response.setForce(force);
        return response;
    }

    private List<CorpusRecord> recognizeAndBuildCorpusRecords(byte[] imageBytes,
                                                              String mimeType,
                                                              String captureBatchId,
                                                              String imageHash,
                                                              String ossBucket,
                                                              String ossObjectKey,
                                                              String provider,
                                                              String model) {
        RecognitionRun recognitionRun = startRecognitionRun(null, captureBatchId, imageHash, provider, model);
        try {
            VisionRecognitionResult recognitionResult = visionRecognitionService.recognize(
                    imageBytes, mimeType, VisionRecognitionOptions.of(provider, model));
            List<CorpusRecord> records = buildSuccessCorpusRecords(recognitionResult, captureBatchId, imageHash, ossBucket, ossObjectKey);
            finishRecognitionRun(recognitionRun, records.get(0).getParseStatus(), records.size(),
                    records.get(0).getModelRawResponse(), null, null);
            return records;
        } catch (VisionRecognitionException ex) {
            CorpusRecord failedCorpusRecord = baseCorpusRecord(captureBatchId, 1, imageHash, ossBucket, ossObjectKey);
            failedCorpusRecord.setParseStatus(ex.getParseStatus());
            failedCorpusRecord.setErrorMessage(ex.getMessage());
            failedCorpusRecord.setModelRawResponse(ex.getModelRawResponse());
            failedCorpusRecord.setTags("[]");
            finishRecognitionRun(recognitionRun, ex.getParseStatus(), 0, ex.getModelRawResponse(), ex.getParseStatus(), ex.getMessage());
            return List.of(failedCorpusRecord);
        }
    }

    private RecognitionRun startRecognitionRun(Long captureRecordId,
                                               String captureBatchId,
                                               String imageHash,
                                               String provider,
                                               String model) {
        LocalDateTime now = LocalDateTime.now();
        RecognitionRun recognitionRun = new RecognitionRun();
        recognitionRun.setCaptureRecordId(captureRecordId);
        recognitionRun.setCaptureId(captureBatchId);
        recognitionRun.setImageHash(imageHash);
        recognitionRun.setProvider(provider);
        recognitionRun.setModel(model);
        recognitionRun.setStatus(STATUS_PROCESSING);
        recognitionRun.setItemCount(0);
        recognitionRun.setStartedAt(now);
        recognitionRun.setCreatedAt(now);
        recognitionRun.setUpdatedAt(now);
        recognitionRunService.save(recognitionRun);
        return recognitionRun;
    }

    private void finishRecognitionRun(RecognitionRun recognitionRun,
                                      String status,
                                      int itemCount,
                                      String modelRawResponse,
                                      String errorType,
                                      String errorMessage) {
        LocalDateTime finishedAt = LocalDateTime.now();
        recognitionRun.setStatus(status);
        recognitionRun.setItemCount(itemCount);
        recognitionRun.setFinishedAt(finishedAt);
        recognitionRun.setDurationMs(Duration.between(recognitionRun.getStartedAt(), finishedAt).toMillis());
        recognitionRun.setModelRawResponse(modelRawResponse);
        recognitionRun.setErrorType(errorType);
        recognitionRun.setErrorMessage(errorMessage);
        recognitionRun.setUpdatedAt(finishedAt);
        recognitionRunService.updateById(recognitionRun);
    }

    private List<CorpusRecord> buildSuccessCorpusRecords(VisionRecognitionResult recognitionResult,
                                                         String captureBatchId,
                                                         String imageHash,
                                                         String ossBucket,
                                                         String ossObjectKey) {
        List<VisionRecognitionItem> recognizedItems = recognitionResult.getItems() == null ? List.of() : recognitionResult.getItems();
        if (recognizedItems.isEmpty()) {
            CorpusRecord emptyResultRecord = baseCorpusRecord(captureBatchId, 1, imageHash, ossBucket, ossObjectKey);
            applyRecognitionResult(emptyResultRecord, recognitionResult);
            emptyResultRecord.setTags("[]");
            emptyResultRecord.setParseStatus(STATUS_EMPTY_RESULT);
            emptyResultRecord.setErrorMessage("Vision recognition returned no visible comment items");
            return List.of(emptyResultRecord);
        }

        List<CorpusRecord> corpusRecords = new ArrayList<>();
        for (int i = 0; i < recognizedItems.size(); i++) {
            VisionRecognitionItem recognizedItem = recognizedItems.get(i);
            Integer commentIndex = recognizedItem.getCommentIndex() == null ? i + 1 : recognizedItem.getCommentIndex();
            CorpusRecord corpusRecord = baseCorpusRecord(captureBatchId, commentIndex, imageHash, ossBucket, ossObjectKey);
            applyRecognitionResult(corpusRecord, recognitionResult);
            corpusRecord.setRawContent(recognizedItem.getRawContent());
            corpusRecord.setTags(toJson(sanitizeTags(recognizedItem.getTags())));
            corpusRecords.add(corpusRecord);
        }
        return corpusRecords;
    }

    private CorpusRecord baseCorpusRecord(String captureBatchId, Integer commentIndex, String imageHash, String ossBucket, String ossObjectKey) {
        LocalDateTime now = LocalDateTime.now();
        CorpusRecord corpusRecord = new CorpusRecord();
        corpusRecord.setCaptureId(captureBatchId);
        corpusRecord.setCommentIndex(commentIndex);
        corpusRecord.setCollectedTime(now);
        corpusRecord.setOssBucket(ossBucket);
        corpusRecord.setOssObjectKey(ossObjectKey);
        corpusRecord.setImageHash(imageHash);
        corpusRecord.setParseStatus(STATUS_SUCCESS);
        corpusRecord.setCreatedAt(now);
        corpusRecord.setUpdatedAt(now);
        return corpusRecord;
    }

    private void applyRecognitionResult(CorpusRecord corpusRecord, VisionRecognitionResult recognitionResult) {
        corpusRecord.setPlatform(recognitionResult.getPlatform());
        corpusRecord.setContextTarget(recognitionResult.getContextTarget());
        corpusRecord.setOriginalPublishTime(parseOriginalPublishTime(recognitionResult.getOriginalPublishTime()));
        corpusRecord.setModelRawResponse(recognitionResult.getModelRawResponse());
        corpusRecord.setParseStatus(STATUS_SUCCESS);
    }

    private CorpusUploadResponse buildResponse(List<CorpusRecord> corpusRecords, String imageHash, boolean duplicate, boolean force) {
        CorpusUploadResponse response = new CorpusUploadResponse();
        response.setImageHash(imageHash);
        response.setDuplicate(duplicate);
        response.setForce(force);
        if (!corpusRecords.isEmpty()) {
            CorpusRecord firstCorpusRecord = corpusRecords.get(0);
            response.setCaptureId(firstCorpusRecord.getCaptureId());
            response.setRecordId(firstCorpusRecord.getId());
            response.setParseStatus(firstCorpusRecord.getParseStatus());
        }
        response.setRecords(corpusRecords.stream()
                .map(corpusRecord -> CorpusRecordResponse.from(corpusRecord, parseTags(corpusRecord.getTags())))
                .toList());
        return response;
    }

    private boolean isSuccessfulRecognition(List<CorpusRecord> corpusRecords) {
        return corpusRecords.stream().allMatch(corpusRecord -> STATUS_SUCCESS.equals(corpusRecord.getParseStatus()));
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

    private String generateCaptureBatchId() {
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
