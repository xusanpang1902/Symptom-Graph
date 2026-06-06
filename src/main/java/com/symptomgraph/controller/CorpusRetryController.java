package com.symptomgraph.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.symptomgraph.dto.CaptureRecordResponse;
import com.symptomgraph.dto.CorpusProcessMessage;
import com.symptomgraph.dto.CorpusRecordResponse;
import com.symptomgraph.entity.CaptureRecord;
import com.symptomgraph.entity.CorpusRecord;
import com.symptomgraph.mq.CorpusProcessMessageProducer;
import com.symptomgraph.service.CaptureRecordService;
import com.symptomgraph.service.CorpusRecordService;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/corpus")
public class CorpusRetryController {

    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final Set<String> RETRYABLE_FINAL_STATUSES = Set.of("MODEL_FAILED", "PARSE_FAILED");

    private final CaptureRecordService captureRecordService;
    private final CorpusRecordService corpusRecordService;
    private final CorpusProcessMessageProducer corpusProcessMessageProducer;
    private final ObjectMapper objectMapper;

    public CorpusRetryController(CaptureRecordService captureRecordService,
                                 CorpusRecordService corpusRecordService,
                                 CorpusProcessMessageProducer corpusProcessMessageProducer,
                                 ObjectMapper objectMapper) {
        this.captureRecordService = captureRecordService;
        this.corpusRecordService = corpusRecordService;
        this.corpusProcessMessageProducer = corpusProcessMessageProducer;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{id}/retry")
    @Transactional
    public CorpusRecordResponse retry(@PathVariable Long id) {
        CorpusRecord record = corpusRecordService.getById(id);
        if (record == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Corpus record not found");
        }
        if (!RETRYABLE_FINAL_STATUSES.contains(record.getParseStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only MODEL_FAILED or PARSE_FAILED records can be retried");
        }

        record.setParseStatus(STATUS_PROCESSING);
        record.setErrorMessage(null);
        record.setRetryCount(0);
        record.setLastErrorType(null);
        record.setLastFailedAt(null);
        record.setUpdatedAt(LocalDateTime.now());
        corpusRecordService.updateById(record);

        corpusProcessMessageProducer.send(buildMessage(record));
        return CorpusRecordResponse.from(record, parseTags(record.getTags()));
    }

    @PostMapping("/capture-records/{id}/retry")
    @Transactional
    public CaptureRecordResponse retryCaptureRecord(@PathVariable Long id) {
        CaptureRecord record = captureRecordService.getById(id);
        if (record == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Capture record not found");
        }
        if (!RETRYABLE_FINAL_STATUSES.contains(record.getProcessStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only MODEL_FAILED or PARSE_FAILED capture records can be retried");
        }

        record.setProcessStatus(STATUS_PROCESSING);
        record.setErrorMessage(null);
        record.setRetryCount(0);
        record.setLastErrorType(null);
        record.setLastFailedAt(null);
        record.setUpdatedAt(LocalDateTime.now());
        captureRecordService.updateById(record);

        corpusProcessMessageProducer.send(buildMessage(record));
        return CaptureRecordResponse.from(record);
    }

    private CorpusProcessMessage buildMessage(CorpusRecord record) {
        CorpusProcessMessage message = new CorpusProcessMessage();
        message.setRecordId(record.getId());
        message.setCaptureId(record.getCaptureId());
        message.setImageHash(record.getImageHash());
        message.setOssBucket(record.getOssBucket());
        message.setOssObjectKey(record.getOssObjectKey());
        message.setMimeType(inferMimeType(record.getOssObjectKey()));
        message.setRetryCount(0);
        return message;
    }

    private CorpusProcessMessage buildMessage(CaptureRecord record) {
        CorpusProcessMessage message = new CorpusProcessMessage();
        message.setCaptureRecordId(record.getId());
        message.setCaptureId(record.getCaptureId());
        message.setImageHash(record.getImageHash());
        message.setOssBucket(record.getOssBucket());
        message.setOssObjectKey(record.getOssObjectKey());
        message.setMimeType(record.getMimeType() == null ? inferMimeType(record.getOssObjectKey()) : record.getMimeType());
        message.setForce(Boolean.TRUE.equals(record.getForce()));
        message.setRetryCount(0);
        return message;
    }

    private String inferMimeType(String objectKey) {
        if (objectKey == null) {
            return "image/png";
        }
        String lowerObjectKey = objectKey.toLowerCase();
        if (lowerObjectKey.endsWith(".jpg") || lowerObjectKey.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lowerObjectKey.endsWith(".webp")) {
            return "image/webp";
        }
        if (lowerObjectKey.endsWith(".gif")) {
            return "image/gif";
        }
        return "image/png";
    }

    private List<String> parseTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(tagsJson, new TypeReference<>() {
            });
        } catch (IOException ex) {
            return List.of();
        }
    }
}
