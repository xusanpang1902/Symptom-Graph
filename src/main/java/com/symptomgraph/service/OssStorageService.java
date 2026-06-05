package com.symptomgraph.service;

import com.symptomgraph.dto.OssUploadResult;
import org.springframework.web.multipart.MultipartFile;

public interface OssStorageService {

    OssUploadResult upload(MultipartFile file, String captureId);

    String generateSignedUrl(String objectKey);

    byte[] download(String objectKey);

    String getBucket();
}
