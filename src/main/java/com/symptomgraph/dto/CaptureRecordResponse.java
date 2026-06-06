package com.symptomgraph.dto;

import com.symptomgraph.entity.CaptureRecord;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CaptureRecordResponse {

    private Long id;

    private String captureId;

    private String imageHash;

    private String ossBucket;

    private String ossObjectKey;

    private String mimeType;

    private String provider;

    private String model;

    private String processStatus;

    private Integer retryCount;

    private String lastErrorType;

    private LocalDateTime lastFailedAt;

    private String errorMessage;

    private Boolean duplicate;

    private Boolean force;

    public static CaptureRecordResponse from(CaptureRecord record) {
        CaptureRecordResponse response = new CaptureRecordResponse();
        response.setId(record.getId());
        response.setCaptureId(record.getCaptureId());
        response.setImageHash(record.getImageHash());
        response.setOssBucket(record.getOssBucket());
        response.setOssObjectKey(record.getOssObjectKey());
        response.setMimeType(record.getMimeType());
        response.setProvider(record.getProvider());
        response.setModel(record.getModel());
        response.setProcessStatus(record.getProcessStatus());
        response.setRetryCount(record.getRetryCount());
        response.setLastErrorType(record.getLastErrorType());
        response.setLastFailedAt(record.getLastFailedAt());
        response.setErrorMessage(record.getErrorMessage());
        response.setDuplicate(record.getDuplicate());
        response.setForce(record.getForce());
        return response;
    }
}
