package com.symptomgraph.controller;

import com.symptomgraph.dto.OssUploadResult;
import com.symptomgraph.service.OssStorageService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Controller
public class OssPreviewController {

    private static final DateTimeFormatter CAPTURE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final OssStorageService ossStorageService;

    public OssPreviewController(OssStorageService ossStorageService) {
        this.ossStorageService = ossStorageService;
    }

    @GetMapping("/oss-preview")
    public String previewForm() {
        return "oss-preview";
    }

    @PostMapping("/oss-preview")
    public String uploadForPreview(@RequestParam("file") MultipartFile file, Model model) {
        String captureId = generateCaptureId();
        OssUploadResult uploadResult = ossStorageService.upload(file, captureId);
        String signedUrl = ossStorageService.generateSignedUrl(uploadResult.getObjectKey());

        model.addAttribute("uploadResult", uploadResult);
        model.addAttribute("captureId", captureId);
        model.addAttribute("signedUrl", signedUrl);
        return "oss-preview";
    }

    private String generateCaptureId() {
        String timestamp = LocalDateTime.now().format(CAPTURE_TIME_FORMATTER);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return timestamp + "_" + suffix;
    }
}
