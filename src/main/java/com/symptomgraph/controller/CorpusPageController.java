package com.symptomgraph.controller;

import com.symptomgraph.dto.CorpusUploadResponse;
import com.symptomgraph.entity.CaptureRecord;
import com.symptomgraph.entity.CorpusRecord;
import com.symptomgraph.service.CaptureRecordService;
import com.symptomgraph.service.CorpusIngestionService;
import com.symptomgraph.service.CorpusRecordService;
import com.symptomgraph.service.OssStorageService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Controller
public class CorpusPageController {

    private final CorpusIngestionService corpusIngestionService;
    private final CaptureRecordService captureRecordService;
    private final CorpusRecordService corpusRecordService;
    private final OssStorageService ossStorageService;

    public CorpusPageController(CorpusIngestionService corpusIngestionService,
                                CaptureRecordService captureRecordService,
                                CorpusRecordService corpusRecordService,
                                OssStorageService ossStorageService) {
        this.corpusIngestionService = corpusIngestionService;
        this.captureRecordService = captureRecordService;
        this.corpusRecordService = corpusRecordService;
        this.ossStorageService = ossStorageService;
    }

    @GetMapping("/corpus/upload")
    public String uploadForm() {
        return "corpus-upload";
    }

    @GetMapping("/corpus/manage")
    public String manage() {
        return "corpus-manage";
    }

    @PostMapping("/corpus/upload")
    public String upload(@RequestParam("file") MultipartFile file,
                         @RequestParam(value = "force", defaultValue = "false") boolean force,
                         Model model) {
        CorpusUploadResponse result = corpusIngestionService.ingest(file, force);
        model.addAttribute("result", result);
        model.addAttribute("signedUrl", resolveSignedUrl(result));
        return "corpus-upload";
    }

    private String resolveSignedUrl(CorpusUploadResponse result) {
        if (result == null || !StringUtils.hasText(result.getCaptureId())) {
            return null;
        }

        List<CorpusRecord> records = corpusRecordService.listByCaptureId(result.getCaptureId());
        if (records.isEmpty() || !StringUtils.hasText(records.get(0).getOssObjectKey())) {
            if (result.getCaptureRecordId() == null) {
                return null;
            }
            CaptureRecord captureRecord = captureRecordService.getById(result.getCaptureRecordId());
            if (captureRecord == null || !StringUtils.hasText(captureRecord.getOssObjectKey())) {
                return null;
            }
            return ossStorageService.generateSignedUrl(captureRecord.getOssObjectKey());
        }
        return ossStorageService.generateSignedUrl(records.get(0).getOssObjectKey());
    }
}
