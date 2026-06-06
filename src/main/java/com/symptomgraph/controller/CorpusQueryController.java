package com.symptomgraph.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.symptomgraph.dto.CaptureRecordResponse;
import com.symptomgraph.dto.CorpusRecordResponse;
import com.symptomgraph.entity.CaptureRecord;
import com.symptomgraph.entity.CorpusRecord;
import com.symptomgraph.service.CaptureRecordService;
import com.symptomgraph.service.CorpusRecordService;
import com.symptomgraph.service.OssStorageService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/corpus")
public class CorpusQueryController {

    private final CorpusRecordService corpusRecordService;
    private final CaptureRecordService captureRecordService;
    private final OssStorageService ossStorageService;
    private final ObjectMapper objectMapper;

    public CorpusQueryController(CorpusRecordService corpusRecordService,
                                 CaptureRecordService captureRecordService,
                                 OssStorageService ossStorageService,
                                 ObjectMapper objectMapper) {
        this.corpusRecordService = corpusRecordService;
        this.captureRecordService = captureRecordService;
        this.ossStorageService = ossStorageService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/{id}")
    public CorpusRecordResponse detail(@PathVariable Long id) {
        CorpusRecord record = corpusRecordService.getById(id);
        if (record == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Corpus record not found");
        }
        return toResponse(record);
    }

    @GetMapping("/captures/{captureId}")
    public List<CorpusRecordResponse> byCapture(@PathVariable String captureId) {
        return corpusRecordService.listByCaptureId(captureId).stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/capture-records/{id}")
    public CaptureRecordResponse captureRecord(@PathVariable Long id) {
        CaptureRecord record = captureRecordService.getById(id);
        if (record == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Capture record not found");
        }
        return CaptureRecordResponse.from(record);
    }

    @GetMapping("/{id}/image-url")
    public Map<String, String> imageUrl(@PathVariable Long id) {
        CorpusRecord record = corpusRecordService.getById(id);
        if (record == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Corpus record not found");
        }
        return Map.of("signedUrl", ossStorageService.generateSignedUrl(record.getOssObjectKey()));
    }

    private CorpusRecordResponse toResponse(CorpusRecord record) {
        return CorpusRecordResponse.from(record, parseTags(record.getTags()));
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
