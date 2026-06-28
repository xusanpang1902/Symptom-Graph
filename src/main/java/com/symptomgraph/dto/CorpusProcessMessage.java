package com.symptomgraph.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CorpusProcessMessage {

    private Long captureRecordId;

    private Long recordId;

    private String captureId;

    private String imageHash;

    private String ossBucket;

    private String ossObjectKey;

    private String mimeType;

    private String provider;

    private String model;

    private boolean force;

    private int retryCount;

    private String lastErrorMessage;

    private String lastErrorType;

    private LocalDateTime lastFailedAt;
}
