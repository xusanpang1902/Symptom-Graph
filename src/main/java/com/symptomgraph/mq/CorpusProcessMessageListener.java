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
    public void handle(CorpusProcessMessage message) {
        CaptureRecord captureRecord = resolveCaptureRecord(message);
        CorpusRecord processingRecord = resolveProcessingRecord(message);
        if (captureRecord == null && processingRecord == null) {
            log.warn("Processing task not found, skip message: captureRecordId={}, recordId={}, captureId={}",
                    message.getCaptureRecordId(), message.getRecordId(), message.getCaptureId());
            return;
        }

        try {
            // 核心网络操作说明：Consumer 在后台线程从私有 OSS 下载原图字节，再调用统一的 VisionRecognitionService。
            // 这里不关心当前 provider 是 Gemini 还是 OpenRouter，从而保留 Milestone 9 已完成的 Provider 策略模式。
            byte[] imageBytes = ossStorageService.download(message.getOssObjectKey());
            VisionRecognitionResult recognitionResult = visionRecognitionService.recognize(imageBytes, message.getMimeType());
            List<CorpusRecord> records = buildRecognitionRecords(captureRecord, processingRecord, recognitionResult);
            if (records.isEmpty()) {
                markCaptureEmpty(captureRecord, recognitionResult);
                return;
            }

            // 数据库状态流转说明：新链路由 capture_record 承载任务状态，corpus_record 只保存识别出的语料。
            // 为兼容旧消息，如果消息仍携带 recordId，则第一条评论继续复用旧 PROCESSING 占位记录。
            persistRecognitionRecords(processingRecord, records);
            markCaptureCompleted(captureRecord, records.get(0));
        } catch (RuntimeException ex) {
            handleFailure(captureRecord, processingRecord, message, failureClassifier.classify(ex));
        }
    }

    private CorpusRecord resolveProcessingRecord(CorpusProcessMessage message) {
        if (message.getRecordId() == null) {
            return null;
        }
        CorpusRecord processingRecord = corpusRecordService.getById(message.getRecordId());
        if (processingRecord == null) {
            log.warn("Corpus processing record not found, continue with capture_record when possible: recordId={}, captureId={}",
                    message.getRecordId(), message.getCaptureId());
        }
        return processingRecord;
    }

    private CaptureRecord resolveCaptureRecord(CorpusProcessMessage message) {
        if (message.getCaptureRecordId() != null) {
            CaptureRecord captureRecord = captureRecordService.getById(message.getCaptureRecordId());
            if (captureRecord != null) {
                return captureRecord;
            }
            log.warn("Capture processing record not found by id, fallback to captureId: captureRecordId={}, captureId={}",
                    message.getCaptureRecordId(), message.getCaptureId());
        }
        if (StringUtils.hasText(message.getCaptureId())) {
            return captureRecordService.getByCaptureId(message.getCaptureId());
        }
        return null;
    }

    private List<CorpusRecord> buildRecognitionRecords(CaptureRecord captureRecord,
                                                       CorpusRecord processingRecord,
                                                       VisionRecognitionResult result) {
        List<VisionRecognitionItem> items = result.getItems() == null ? List.of() : result.getItems();
        if (items.isEmpty()) {
            if (processingRecord == null) {
                return List.of();
            }
            CorpusRecord record = copyBase(processingRecord, processingRecord.getCommentIndex());
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
            CorpusRecord record = processingRecord == null
                    ? copyBase(captureRecord, commentIndex)
                    : copyBase(processingRecord, commentIndex);
            if (i == 0 && processingRecord != null) {
                record.setId(processingRecord.getId());
            }
            applyRecognitionResult(record, result);
            record.setRawContent(item.getRawContent());
            record.setTags(toJson(sanitizeTags(item.getTags())));
            records.add(record);
        }
        return records;
    }

    private void persistRecognitionRecords(CorpusRecord processingRecord, List<CorpusRecord> records) {
        CorpusRecord firstRecord = records.get(0);
        if (processingRecord == null) {
            corpusRecordService.saveBatch(records);
        } else {
            firstRecord.setId(processingRecord.getId());

            List<CorpusRecord> additionalRecords = records.size() <= 1 ? List.of() : records.subList(1, records.size());
            if (!additionalRecords.isEmpty()) {
                corpusRecordService.saveBatch(additionalRecords);
            }
        }

        for (CorpusRecord record : records) {
            if (STATUS_SUCCESS.equals(record.getParseStatus())) {
                record.setMarkdownPath(markdownExportService.export(record));
            }
        }
        corpusRecordService.updateBatchById(records);
    }

    private void handleFailure(CaptureRecord captureRecord,
                               CorpusRecord record,
                               CorpusProcessMessage message,
                               CorpusProcessFailure failure) {
        int nextRetryCount = message.getRetryCount() + 1;
        LocalDateTime failedAt = LocalDateTime.now();
        if (failure.retryable() && nextRetryCount <= rabbitMqProperties.getMaxRetryAttempts()) {
            if (record != null) {
                record.setRetryCount(nextRetryCount);
                record.setLastErrorType(failure.errorType());
                record.setLastFailedAt(failedAt);
                record.setErrorMessage(failure.errorMessage());
                record.setModelRawResponse(firstText(failure.modelRawResponse(), record.getModelRawResponse()));
                record.setUpdatedAt(failedAt);
                corpusRecordService.updateById(record);
            }
            markCaptureRetrying(captureRecord, failure, nextRetryCount, failedAt);

            CorpusProcessMessage retryMessage = copyMessage(message);
            retryMessage.setRetryCount(nextRetryCount);
            retryMessage.setLastErrorType(failure.errorType());
            retryMessage.setLastErrorMessage(failure.errorMessage());
            retryMessage.setLastFailedAt(failedAt);
            corpusProcessMessageProducer.sendRetry(retryMessage);
            log.warn("Corpus processing retry scheduled: captureRecordId={}, recordId={}, captureId={}, retry={}/{}, errorType={}, error={}",
                    message.getCaptureRecordId(), message.getRecordId(), message.getCaptureId(), nextRetryCount, rabbitMqProperties.getMaxRetryAttempts(),
                    failure.errorType(), failure.errorMessage());
            return;
        }

        markFinalFailed(captureRecord, record, failure, nextRetryCount, failedAt);
        CorpusProcessMessage deadLetterMessage = copyMessage(message);
        deadLetterMessage.setRetryCount(nextRetryCount);
        deadLetterMessage.setLastErrorType(failure.errorType());
        deadLetterMessage.setLastErrorMessage(failure.errorMessage());
        deadLetterMessage.setLastFailedAt(failedAt);
        corpusProcessMessageProducer.sendDeadLetter(deadLetterMessage);
    }

    private void markFinalFailed(CaptureRecord captureRecord,
                                 CorpusRecord record,
                                 CorpusProcessFailure failure,
                                 int retryCount,
                                 LocalDateTime failedAt) {
        if (record != null) {
            record.setParseStatus(StringUtils.hasText(failure.parseStatus()) ? failure.parseStatus() : STATUS_PARSE_FAILED);
            record.setErrorMessage(failure.errorMessage());
            record.setModelRawResponse(firstText(failure.modelRawResponse(), record.getModelRawResponse()));
            record.setTags(StringUtils.hasText(record.getTags()) ? record.getTags() : "[]");
            record.setRetryCount(retryCount);
            record.setLastErrorType(failure.errorType());
            record.setLastFailedAt(failedAt);
            record.setUpdatedAt(failedAt);
            corpusRecordService.updateById(record);
        }
        if (captureRecord != null) {
            applyCaptureFailure(captureRecord, failure, retryCount, failedAt,
                    StringUtils.hasText(failure.parseStatus()) ? failure.parseStatus() : STATUS_PARSE_FAILED);
            captureRecordService.updateById(captureRecord);
        }
        log.warn("Corpus processing failed finally: captureRecordId={}, recordId={}, captureId={}, retryCount={}, errorType={}, error={}",
                captureRecord == null ? null : captureRecord.getId(), record == null ? null : record.getId(),
                captureRecord == null ? null : captureRecord.getCaptureId(), retryCount,
                failure.errorType(), failure.errorMessage());
    }

    private void markCaptureCompleted(CaptureRecord captureRecord, CorpusRecord firstRecord) {
        if (captureRecord == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        captureRecord.setProcessStatus(firstRecord.getParseStatus());
        captureRecord.setErrorMessage(firstRecord.getErrorMessage());
        captureRecord.setModelRawResponse(firstRecord.getModelRawResponse());
        captureRecord.setRetryCount(0);
        captureRecord.setLastErrorType(null);
        captureRecord.setLastFailedAt(null);
        captureRecord.setUpdatedAt(now);
        captureRecordService.updateById(captureRecord);
    }

    private void markCaptureEmpty(CaptureRecord captureRecord, VisionRecognitionResult result) {
        if (captureRecord == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        captureRecord.setProcessStatus(STATUS_EMPTY_RESULT);
        captureRecord.setErrorMessage("Vision recognition returned no visible comment items");
        captureRecord.setModelRawResponse(result.getModelRawResponse());
        captureRecord.setRetryCount(0);
        captureRecord.setLastErrorType(null);
        captureRecord.setLastFailedAt(null);
        captureRecord.setUpdatedAt(now);
        captureRecordService.updateById(captureRecord);
    }

    private void markCaptureRetrying(CaptureRecord captureRecord,
                                     CorpusProcessFailure failure,
                                     int retryCount,
                                     LocalDateTime failedAt) {
        if (captureRecord == null) {
            return;
        }
        applyCaptureFailure(captureRecord, failure, retryCount, failedAt, STATUS_PROCESSING);
        captureRecordService.updateById(captureRecord);
    }

    private void applyCaptureFailure(CaptureRecord captureRecord,
                                     CorpusProcessFailure failure,
                                     int retryCount,
                                     LocalDateTime failedAt,
                                     String status) {
        captureRecord.setProcessStatus(status);
        captureRecord.setErrorMessage(failure.errorMessage());
        captureRecord.setModelRawResponse(firstText(failure.modelRawResponse(), captureRecord.getModelRawResponse()));
        captureRecord.setRetryCount(retryCount);
        captureRecord.setLastErrorType(failure.errorType());
        captureRecord.setLastFailedAt(failedAt);
        captureRecord.setUpdatedAt(failedAt);
    }

    private CorpusProcessMessage copyMessage(CorpusProcessMessage source) {
        CorpusProcessMessage message = new CorpusProcessMessage();
        message.setCaptureRecordId(source.getCaptureRecordId());
        message.setRecordId(source.getRecordId());
        message.setCaptureId(source.getCaptureId());
        message.setImageHash(source.getImageHash());
        message.setOssBucket(source.getOssBucket());
        message.setOssObjectKey(source.getOssObjectKey());
        message.setMimeType(source.getMimeType());
        message.setForce(source.isForce());
        return message;
    }

    private String firstText(String first, String fallback) {
        return StringUtils.hasText(first) ? first : fallback;
    }

    private CorpusRecord copyBase(CorpusRecord source, Integer commentIndex) {
        CorpusRecord record = new CorpusRecord();
        record.setCaptureId(source.getCaptureId());
        record.setCommentIndex(commentIndex);
        record.setCollectedTime(source.getCollectedTime());
        record.setOssBucket(source.getOssBucket());
        record.setOssObjectKey(source.getOssObjectKey());
        record.setImageHash(source.getImageHash());
        record.setCreatedAt(source.getCreatedAt());
        record.setUpdatedAt(LocalDateTime.now());
        record.setRetryCount(source.getRetryCount());
        record.setLastErrorType(source.getLastErrorType());
        record.setLastFailedAt(source.getLastFailedAt());
        return record;
    }

    private CorpusRecord copyBase(CaptureRecord source, Integer commentIndex) {
        LocalDateTime now = LocalDateTime.now();
        CorpusRecord record = new CorpusRecord();
        record.setCaptureId(source.getCaptureId());
        record.setCommentIndex(commentIndex);
        record.setCollectedTime(now);
        record.setOssBucket(source.getOssBucket());
        record.setOssObjectKey(source.getOssObjectKey());
        record.setImageHash(source.getImageHash());
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        record.setRetryCount(source.getRetryCount());
        record.setLastErrorType(source.getLastErrorType());
        record.setLastFailedAt(source.getLastFailedAt());
        return record;
    }

    private void applyRecognitionResult(CorpusRecord record, VisionRecognitionResult result) {
        record.setPlatform(result.getPlatform());
        record.setContextTarget(result.getContextTarget());
        record.setOriginalPublishTime(parseOriginalPublishTime(result.getOriginalPublishTime()));
        record.setModelRawResponse(result.getModelRawResponse());
        record.setParseStatus(STATUS_SUCCESS);
        record.setErrorMessage(null);
        record.setLastErrorType(null);
        record.setLastFailedAt(null);
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
