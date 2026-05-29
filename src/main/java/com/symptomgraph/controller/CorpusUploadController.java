package com.symptomgraph.controller;

import com.symptomgraph.dto.CorpusUploadResponse;
import com.symptomgraph.service.CorpusIngestionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/corpus")
public class CorpusUploadController {

    private final CorpusIngestionService corpusIngestionService;

    public CorpusUploadController(CorpusIngestionService corpusIngestionService) {
        this.corpusIngestionService = corpusIngestionService;
    }

    @PostMapping("/upload")
    public CorpusUploadResponse upload(@RequestParam("file") MultipartFile file,
                                       @RequestParam(value = "force", defaultValue = "false") boolean force) {
        return corpusIngestionService.ingest(file, force);
    }
}
