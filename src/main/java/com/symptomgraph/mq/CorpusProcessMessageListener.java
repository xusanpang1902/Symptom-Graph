package com.symptomgraph.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.symptomgraph.config.CorpusRabbitMqProperties;
import com.symptomgraph.dto.CorpusProcessMessage;
import com.symptomgraph.dto.VisionRecognitionItem;
import com.symptomgraph.dto.VisionRecognitionResult;
import com.symptomgraph.entity.CaptureRecord;
import com.symptomgraph.entity.CorpusRecord;
import com.symptomgraph.service.CaptureRecordService;
import com.symptomgraph.service.CorpusRecordService;
import com.symptomgraph.service.MarkdownExportService;
import com.symptomgraph.service.OssStorageService;
import com.symptomgraph.service.VisionRecognitionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Component
public class CorpusProcessMessageListener {

    private static final Logger log = LoggerFactory.getLogger(CorpusProcessMessageListener.class);
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_EMPTY_RESULT = "EMPTY_RESULT";
    private static final String STATUS_PARSE_FAILED = "PARSE_FAILED";
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CaptureRecordService captureRecordService;
    private final CorpusRecordService corpusRecordService;
    private final OssStorageService ossStorageService;
    private final VisionRecognitionService visionRecognitionService;
    private final MarkdownExportService markdownExportService;
    private final CorpusProcessMessageProducer corpusProcessMessageProducer;
    private final CorpusProcessFailureClassifier failureClassifier;
    private final CorpusRabbitMqProperties rabbitMqProperties;
    private final ObjectMapper objectMapper;

    public CorpusProcessMessageListener(CaptureRecordService captureRecordService,
                                        CorpusRecordService corpusRecordService,
                                        OssStorageService ossStorageService,
                                        VisionRecognitionService visionRecognitionService,
                                        MarkdownExportService markdownExportService,
                                        CorpusProcessMessageProducer corpusProcessMessageProducer,
                                        CorpusProcessFailureClassifier failureClassifier,
                                        CorpusRabbitMqProperties rabbitMqProperties,
                                        ObjectMapper objectMapper) {
        this.captureRecordService = captureRecordService;
        this.corpusRecordService = corpusRecordService;
        this.ossStorageService = ossStorageService;
        this.visionRecognitionService = visionRecognitionService;
        this.markdownExportService = markdownExportService;
        this.corpusProcessMessageProducer = corpusProcessMessageProducer;
        this.failureClassifier = failureClassifier;
        this.rabbitMqProperties = rabbitMqProperties;
        this.objectMapper = objectMapper;
    }

    @Transactional
    @RabbitListener(queues = "${app.rabbitmq.corpus-process-queue}", autoStartup = "${app.rabbitmq.listener-auto-startup:true}")
    public void handle(CorpusProcessMessage processMessage) {
        CaptureRecord captureTask = resolveCaptureTask(processMessage);
        CorpusRecord legacyProcessingCorpusRecord = resolveLegacyProcessingCorpusRecord(processMessage);
        if (captureTask == null && legacyProcessingCorpusRecord == null) {
            log.warn("Processing task not found, skip message: captureRecordId={}, recordId={}, captureId={}",
                    processMessage.getCaptureRecordId(), processMessage.getRecordId(), processMessage.getCaptureId());
            return;
        }

        try {
            // 核心网络操作说明：Consumer 在后台线程从私有 OSS 下载原图字节，再调用统一的 VisionRecognitionService。
            // 这里不关心当前 provider 是 Gemini 还是 OpenRouter，从而保留 Milestone 9 已完成的 Provider 策略模式。
            byte[] imageBytes = ossStorageService.download(processMessage.getOssObjectKey());
            VisionRecognitionResult recognitionResult = visionRecognitionService.recognize(imageBytes, processMessage.getMimeType());
            List<CorpusRecord> recognizedCorpusRecords = buildRecognitionCorpusRecords(
                    captureTask, legacyProcessingCorpusRecord, recognitionResult);
            if (recognizedCorpusRecords.isEmpty()) {
                markCaptureEmpty(captureTask, recognitionResult);
                return;
            }

            // 数据库状态流转说明：新链路由 capture_record 承载任务状态，corpus_record 只保存识别出的语料。
            // 为兼容旧消息，如果消息仍携带 recordId，则第一条评论继续复用旧 PROCESSING 占位记录。
            persistRecognitionCorpusRecords(legacyProcessingCorpusRecord, recognizedCorpusRecords);
            markCaptureCompleted(captureTask, recognizedCorpusRecords.get(0));
        } catch (RuntimeException ex) {
            handleFailure(captureTask, legacyProcessingCorpusRecord, processMessage, failureClassifier.classify(ex));
        }
    }

    private CorpusRecord resolveLegacyProcessingCorpusRecord(CorpusProcessMessage processMessage) {
        if (processMessage.getRecordId() == null) {
            return null;
        }
        CorpusRecord legacyProcessingCorpusRecord = corpusRecordService.getById(processMessage.getRecordId());
        if (legacyProcessingCorpusRecord == null) {
            log.warn("Corpus processing record not found, continue with capture_record when possible: recordId={}, captureId={}",
                    processMessage.getRecordId(), processMessage.getCaptureId());
        }
        return legacyProcessingCorpusRecord;
    }

    private CaptureRecord resolveCaptureTask(CorpusProcessMessage processMessage) {
        if (processMessage.getCaptureRecordId() != null) {
            CaptureRecord captureTask = captureRecordService.getById(processMessage.getCaptureRecordId());
            if (captureTask != null) {
                return captureTask;
            }
            log.warn("Capture processing record not found by id, fallback to captureId: captureRecordId={}, captureId={}",
                    processMessage.getCaptureRecordId(), processMessage.getCaptureId());
        }
        if (StringUtils.hasText(processMessage.getCaptureId())) {
            return captureRecordService.getByCaptureId(processMessage.getCaptureId());
        }
        return null;
    }

    private List<CorpusRecord> buildRecognitionCorpusRecords(CaptureRecord captureTask,
                                                             CorpusRecord legacyProcessingCorpusRecord,
                                                             VisionRecognitionResult recognitionResult) {
        List<VisionRecognitionItem> recognizedItems = recognitionResult.getItems() == null ? List.of() : recognitionResult.getItems();
        if (recognizedItems.isEmpty()) {
            if (legacyProcessingCorpusRecord == null) {
                return List.of();
            }
            CorpusRecord emptyResultRecord = copyBase(legacyProcessingCorpusRecord, legacyProcessingCorpusRecord.getCommentIndex());
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
            CorpusRecord corpusRecord = legacyProcessingCorpusRecord == null
                    ? copyBase(captureTask, commentIndex)
                    : copyBase(legacyProcessingCorpusRecord, commentIndex);
            if (i == 0 && legacyProcessingCorpusRecord != null) {
                corpusRecord.setId(legacyProcessingCorpusRecord.getId());
            }
            applyRecognitionResult(corpusRecord, recognitionResult);
            corpusRecord.setRawContent(recognizedItem.getRawContent());
            corpusRecord.setTags(toJson(sanitizeTags(recognizedItem.getTags())));
            corpusRecords.add(corpusRecord);
        }
        return corpusRecords;
    }

    private void persistRecognitionCorpusRecords(CorpusRecord legacyProcessingCorpusRecord, List<CorpusRecord> corpusRecords) {
        CorpusRecord firstCorpusRecord = corpusRecords.get(0);
        if (legacyProcessingCorpusRecord == null) {
            corpusRecordService.saveBatch(corpusRecords);
        } else {
            firstCorpusRecord.setId(legacyProcessingCorpusRecord.getId());

            List<CorpusRecord> additionalCorpusRecords = corpusRecords.size() <= 1 ? List.of() : corpusRecords.subList(1, corpusRecords.size());
            if (!additionalCorpusRecords.isEmpty()) {
                corpusRecordService.saveBatch(additionalCorpusRecords);
            }
        }

        for (CorpusRecord corpusRecord : corpusRecords) {
            if (STATUS_SUCCESS.equals(corpusRecord.getParseStatus())) {
                corpusRecord.setMarkdownPath(markdownExportService.export(corpusRecord));
            }
        }
        corpusRecordService.updateBatchById(corpusRecords);
    }

    private void handleFailure(CaptureRecord captureTask,
                               CorpusRecord legacyProcessingCorpusRecord,
                               CorpusProcessMessage processMessage,
                               CorpusProcessFailure failure) {
        int nextRetryCount = processMessage.getRetryCount() + 1;
        LocalDateTime failedAt = LocalDateTime.now();
        if (failure.retryable() && nextRetryCount <= rabbitMqProperties.getMaxRetryAttempts()) {
            if (legacyProcessingCorpusRecord != null) {
                legacyProcessingCorpusRecord.setRetryCount(nextRetryCount);
                legacyProcessingCorpusRecord.setLastErrorType(failure.errorType());
                legacyProcessingCorpusRecord.setLastFailedAt(failedAt);
                legacyProcessingCorpusRecord.setErrorMessage(failure.errorMessage());
                legacyProcessingCorpusRecord.setModelRawResponse(firstText(failure.modelRawResponse(), legacyProcessingCorpusRecord.getModelRawResponse()));
                legacyProcessingCorpusRecord.setUpdatedAt(failedAt);
                corpusRecordService.updateById(legacyProcessingCorpusRecord);
            }
            markCaptureRetrying(captureTask, failure, nextRetryCount, failedAt);

            CorpusProcessMessage retryMessage = copyMessage(processMessage);
            retryMessage.setRetryCount(nextRetryCount);
            retryMessage.setLastErrorType(failure.errorType());
            retryMessage.setLastErrorMessage(failure.errorMessage());
            retryMessage.setLastFailedAt(failedAt);
            corpusProcessMessageProducer.sendRetry(retryMessage);
            log.warn("Corpus processing retry scheduled: captureRecordId={}, recordId={}, captureId={}, retry={}/{}, errorType={}, error={}",
                    processMessage.getCaptureRecordId(), processMessage.getRecordId(), processMessage.getCaptureId(), nextRetryCount, rabbitMqProperties.getMaxRetryAttempts(),
                    failure.errorType(), failure.errorMessage());
            return;
        }

        markFinalFailed(captureTask, legacyProcessingCorpusRecord, failure, nextRetryCount, failedAt);
        CorpusProcessMessage deadLetterMessage = copyMessage(processMessage);
        deadLetterMessage.setRetryCount(nextRetryCount);
        deadLetterMessage.setLastErrorType(failure.errorType());
        deadLetterMessage.setLastErrorMessage(failure.errorMessage());
        deadLetterMessage.setLastFailedAt(failedAt);
        corpusProcessMessageProducer.sendDeadLetter(deadLetterMessage);
    }

    private void markFinalFailed(CaptureRecord captureTask,
                                 CorpusRecord legacyProcessingCorpusRecord,
                                 CorpusProcessFailure failure,
                                 int retryCount,
                                 LocalDateTime failedAt) {
        if (legacyProcessingCorpusRecord != null) {
            legacyProcessingCorpusRecord.setParseStatus(StringUtils.hasText(failure.parseStatus()) ? failure.parseStatus() : STATUS_PARSE_FAILED);
            legacyProcessingCorpusRecord.setErrorMessage(failure.errorMessage());
            legacyProcessingCorpusRecord.setModelRawResponse(firstText(failure.modelRawResponse(), legacyProcessingCorpusRecord.getModelRawResponse()));
            legacyProcessingCorpusRecord.setTags(StringUtils.hasText(legacyProcessingCorpusRecord.getTags()) ? legacyProcessingCorpusRecord.getTags() : "[]");
            legacyProcessingCorpusRecord.setRetryCount(retryCount);
            legacyProcessingCorpusRecord.setLastErrorType(failure.errorType());
            legacyProcessingCorpusRecord.setLastFailedAt(failedAt);
            legacyProcessingCorpusRecord.setUpdatedAt(failedAt);
            corpusRecordService.updateById(legacyProcessingCorpusRecord);
        }
        if (captureTask != null) {
            applyCaptureFailure(captureTask, failure, retryCount, failedAt,
                    StringUtils.hasText(failure.parseStatus()) ? failure.parseStatus() : STATUS_PARSE_FAILED);
            captureRecordService.updateById(captureTask);
        }
        log.warn("Corpus processing failed finally: captureRecordId={}, recordId={}, captureId={}, retryCount={}, errorType={}, error={}",
                captureTask == null ? null : captureTask.getId(),
                legacyProcessingCorpusRecord == null ? null : legacyProcessingCorpusRecord.getId(),
                captureTask == null ? null : captureTask.getCaptureId(), retryCount,
                failure.errorType(), failure.errorMessage());
    }

    private void markCaptureCompleted(CaptureRecord captureTask, CorpusRecord firstCorpusRecord) {
        if (captureTask == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        captureTask.setProcessStatus(firstCorpusRecord.getParseStatus());
        captureTask.setErrorMessage(firstCorpusRecord.getErrorMessage());
        captureTask.setModelRawResponse(firstCorpusRecord.getModelRawResponse());
        captureTask.setRetryCount(0);
        captureTask.setLastErrorType(null);
        captureTask.setLastFailedAt(null);
        captureTask.setUpdatedAt(now);
        captureRecordService.updateById(captureTask);
    }

    private void markCaptureEmpty(CaptureRecord captureTask, VisionRecognitionResult recognitionResult) {
        if (captureTask == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        captureTask.setProcessStatus(STATUS_EMPTY_RESULT);
        captureTask.setErrorMessage("Vision recognition returned no visible comment items");
        captureTask.setModelRawResponse(recognitionResult.getModelRawResponse());
        captureTask.setRetryCount(0);
        captureTask.setLastErrorType(null);
        captureTask.setLastFailedAt(null);
        captureTask.setUpdatedAt(now);
        captureRecordService.updateById(captureTask);
    }

    private void markCaptureRetrying(CaptureRecord captureTask,
                                     CorpusProcessFailure failure,
                                     int retryCount,
                                     LocalDateTime failedAt) {
        if (captureTask == null) {
            return;
        }
        applyCaptureFailure(captureTask, failure, retryCount, failedAt, STATUS_PROCESSING);
        captureRecordService.updateById(captureTask);
    }

    private void applyCaptureFailure(CaptureRecord captureTask,
                                     CorpusProcessFailure failure,
                                     int retryCount,
                                     LocalDateTime failedAt,
                                     String status) {
        captureTask.setProcessStatus(status);
        captureTask.setErrorMessage(failure.errorMessage());
        captureTask.setModelRawResponse(firstText(failure.modelRawResponse(), captureTask.getModelRawResponse()));
        captureTask.setRetryCount(retryCount);
        captureTask.setLastErrorType(failure.errorType());
        captureTask.setLastFailedAt(failedAt);
        captureTask.setUpdatedAt(failedAt);
    }

    private CorpusProcessMessage copyMessage(CorpusProcessMessage source) {
        CorpusProcessMessage processMessage = new CorpusProcessMessage();
        processMessage.setCaptureRecordId(source.getCaptureRecordId());
        processMessage.setRecordId(source.getRecordId());
        processMessage.setCaptureId(source.getCaptureId());
        processMessage.setImageHash(source.getImageHash());
        processMessage.setOssBucket(source.getOssBucket());
        processMessage.setOssObjectKey(source.getOssObjectKey());
        processMessage.setMimeType(source.getMimeType());
        processMessage.setForce(source.isForce());
        return processMessage;
    }

    private String firstText(String first, String fallback) {
        return StringUtils.hasText(first) ? first : fallback;
    }

    private CorpusRecord copyBase(CorpusRecord sourceCorpusRecord, Integer commentIndex) {
        CorpusRecord corpusRecord = new CorpusRecord();
        corpusRecord.setCaptureId(sourceCorpusRecord.getCaptureId());
        corpusRecord.setCommentIndex(commentIndex);
        corpusRecord.setCollectedTime(sourceCorpusRecord.getCollectedTime());
        corpusRecord.setOssBucket(sourceCorpusRecord.getOssBucket());
        corpusRecord.setOssObjectKey(sourceCorpusRecord.getOssObjectKey());
        corpusRecord.setImageHash(sourceCorpusRecord.getImageHash());
        corpusRecord.setCreatedAt(sourceCorpusRecord.getCreatedAt());
        corpusRecord.setUpdatedAt(LocalDateTime.now());
        corpusRecord.setRetryCount(sourceCorpusRecord.getRetryCount());
        corpusRecord.setLastErrorType(sourceCorpusRecord.getLastErrorType());
        corpusRecord.setLastFailedAt(sourceCorpusRecord.getLastFailedAt());
        return corpusRecord;
    }

    private CorpusRecord copyBase(CaptureRecord sourceCaptureTask, Integer commentIndex) {
        LocalDateTime now = LocalDateTime.now();
        CorpusRecord corpusRecord = new CorpusRecord();
        corpusRecord.setCaptureId(sourceCaptureTask.getCaptureId());
        corpusRecord.setCommentIndex(commentIndex);
        corpusRecord.setCollectedTime(now);
        corpusRecord.setOssBucket(sourceCaptureTask.getOssBucket());
        corpusRecord.setOssObjectKey(sourceCaptureTask.getOssObjectKey());
        corpusRecord.setImageHash(sourceCaptureTask.getImageHash());
        corpusRecord.setCreatedAt(now);
        corpusRecord.setUpdatedAt(now);
        corpusRecord.setRetryCount(sourceCaptureTask.getRetryCount());
        corpusRecord.setLastErrorType(sourceCaptureTask.getLastErrorType());
        corpusRecord.setLastFailedAt(sourceCaptureTask.getLastFailedAt());
        return corpusRecord;
    }

    private void applyRecognitionResult(CorpusRecord corpusRecord, VisionRecognitionResult recognitionResult) {
        corpusRecord.setPlatform(recognitionResult.getPlatform());
        corpusRecord.setContextTarget(recognitionResult.getContextTarget());
        corpusRecord.setOriginalPublishTime(parseOriginalPublishTime(recognitionResult.getOriginalPublishTime()));
        corpusRecord.setModelRawResponse(recognitionResult.getModelRawResponse());
        corpusRecord.setParseStatus(STATUS_SUCCESS);
        corpusRecord.setErrorMessage(null);
        corpusRecord.setLastErrorType(null);
        corpusRecord.setLastFailedAt(null);
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
