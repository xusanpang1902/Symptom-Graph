package com.symptomgraph.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.symptomgraph.dto.CorpusProcessMessage;
import com.symptomgraph.dto.VisionRecognitionItem;
import com.symptomgraph.dto.VisionRecognitionResult;
import com.symptomgraph.entity.CorpusRecord;
import com.symptomgraph.exception.VisionRecognitionException;
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
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CorpusRecordService corpusRecordService;
    private final OssStorageService ossStorageService;
    private final VisionRecognitionService visionRecognitionService;
    private final MarkdownExportService markdownExportService;
    private final ObjectMapper objectMapper;

    public CorpusProcessMessageListener(CorpusRecordService corpusRecordService,
                                        OssStorageService ossStorageService,
                                        VisionRecognitionService visionRecognitionService,
                                        MarkdownExportService markdownExportService,
                                        ObjectMapper objectMapper) {
        this.corpusRecordService = corpusRecordService;
        this.ossStorageService = ossStorageService;
        this.visionRecognitionService = visionRecognitionService;
        this.markdownExportService = markdownExportService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    @RabbitListener(queues = "${app.rabbitmq.corpus-process-queue}", autoStartup = "${app.rabbitmq.listener-auto-startup:true}")
    public void handle(CorpusProcessMessage message) {
        CorpusRecord processingRecord = corpusRecordService.getById(message.getRecordId());
        if (processingRecord == null) {
            log.warn("Corpus processing record not found, skip message: recordId={}, captureId={}",
                    message.getRecordId(), message.getCaptureId());
            return;
        }

        try {
            // 核心网络操作说明：Consumer 在后台线程从私有 OSS 下载原图字节，再调用统一的 VisionRecognitionService。
            // 这里不关心当前 provider 是 Gemini 还是 OpenRouter，从而保留 Milestone 9 已完成的 Provider 策略模式。
            byte[] imageBytes = ossStorageService.download(message.getOssObjectKey());
            VisionRecognitionResult recognitionResult = visionRecognitionService.recognize(imageBytes, message.getMimeType());
            List<CorpusRecord> records = buildRecognitionRecords(processingRecord, recognitionResult);

            // 数据库状态流转说明：上传阶段只写入一条 PROCESSING 占位记录。
            // 识别成功后，第一条评论复用该记录，第二条及后续评论新增记录；空结果则只更新占位记录为 EMPTY_RESULT。
            persistRecognitionRecords(processingRecord, records);
        } catch (VisionRecognitionException ex) {
            markFailed(processingRecord, ex.getParseStatus(), ex.getMessage(), ex.getModelRawResponse());
        } catch (RuntimeException ex) {
            markFailed(processingRecord, STATUS_PARSE_FAILED, ex.getMessage(), processingRecord.getModelRawResponse());
        }
    }

    private List<CorpusRecord> buildRecognitionRecords(CorpusRecord processingRecord, VisionRecognitionResult result) {
        List<VisionRecognitionItem> items = result.getItems() == null ? List.of() : result.getItems();
        if (items.isEmpty()) {
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
            CorpusRecord record = copyBase(processingRecord, commentIndex);
            if (i == 0) {
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
        firstRecord.setId(processingRecord.getId());

        List<CorpusRecord> additionalRecords = records.size() <= 1 ? List.of() : records.subList(1, records.size());
        if (!additionalRecords.isEmpty()) {
            corpusRecordService.saveBatch(additionalRecords);
        }

        for (CorpusRecord record : records) {
            if (STATUS_SUCCESS.equals(record.getParseStatus())) {
                record.setMarkdownPath(markdownExportService.export(record));
            }
        }
        corpusRecordService.updateBatchById(records);
    }

    private void markFailed(CorpusRecord record, String parseStatus, String errorMessage, String modelRawResponse) {
        record.setParseStatus(StringUtils.hasText(parseStatus) ? parseStatus : STATUS_PARSE_FAILED);
        record.setErrorMessage(errorMessage);
        record.setModelRawResponse(modelRawResponse);
        record.setTags(StringUtils.hasText(record.getTags()) ? record.getTags() : "[]");
        record.setUpdatedAt(LocalDateTime.now());
        corpusRecordService.updateById(record);
        log.warn("Corpus processing failed: recordId={}, captureId={}, status={}, error={}",
                record.getId(), record.getCaptureId(), record.getParseStatus(), errorMessage);
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
        return record;
    }

    private void applyRecognitionResult(CorpusRecord record, VisionRecognitionResult result) {
        record.setPlatform(result.getPlatform());
        record.setContextTarget(result.getContextTarget());
        record.setOriginalPublishTime(parseOriginalPublishTime(result.getOriginalPublishTime()));
        record.setModelRawResponse(result.getModelRawResponse());
        record.setParseStatus(STATUS_SUCCESS);
        record.setErrorMessage(null);
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
