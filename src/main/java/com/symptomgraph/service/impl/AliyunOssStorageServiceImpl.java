package com.symptomgraph.service.impl;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ObjectMetadata;
import com.symptomgraph.config.OssProperties;
import com.symptomgraph.dto.OssUploadResult;
import com.symptomgraph.service.OssStorageService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.UUID;

@Service
public class AliyunOssStorageServiceImpl implements OssStorageService {

    private static final DateTimeFormatter YEAR_MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM");

    private final OSS ossClient;
    private final OssProperties properties;

    public AliyunOssStorageServiceImpl(OSS ossClient, OssProperties properties) {
        this.ossClient = ossClient;
        this.properties = properties;
    }

    @Override
    public OssUploadResult upload(MultipartFile file, String captureId) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file must not be empty");
        }
        if (!StringUtils.hasText(captureId)) {
            throw new IllegalArgumentException("captureId must not be blank");
        }

        String objectKey = buildObjectKey(file.getOriginalFilename(), captureId);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(file.getSize());
        if (StringUtils.hasText(file.getContentType())) {
            metadata.setContentType(file.getContentType());
        }

        try {
            ossClient.putObject(properties.getBucket(), objectKey, file.getInputStream(), metadata);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read uploaded file", e);
        }

        return new OssUploadResult(
                properties.getBucket(),
                objectKey,
                file.getOriginalFilename(),
                file.getContentType(),
                file.getSize()
        );
    }

    @Override
    public String generateSignedUrl(String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            throw new IllegalArgumentException("objectKey must not be blank");
        }

        Date expiration = Date.from(
                java.time.Instant.now().plusSeconds(properties.getSignedUrlExpirationMinutes() * 60)
        );
        return ossClient.generatePresignedUrl(properties.getBucket(), objectKey, expiration).toString();
    }

    @Override
    public String getBucket() {
        return properties.getBucket();
    }

    private String buildObjectKey(String originalFilename, String captureId) {
        String prefix = normalizePrefix(properties.getObjectPrefix());
        String yearMonth = LocalDate.now(ZoneId.systemDefault()).format(YEAR_MONTH_FORMATTER);
        String extension = getExtension(originalFilename);
        return prefix + yearMonth + "/" + captureId + "/" + UUID.randomUUID() + extension;
    }

    private String normalizePrefix(String prefix) {
        if (!StringUtils.hasText(prefix)) {
            return "";
        }
        return prefix.endsWith("/") ? prefix : prefix + "/";
    }

    private String getExtension(String filename) {
        String extension = StringUtils.getFilenameExtension(filename);
        return StringUtils.hasText(extension) ? "." + extension.toLowerCase() : "";
    }
}
