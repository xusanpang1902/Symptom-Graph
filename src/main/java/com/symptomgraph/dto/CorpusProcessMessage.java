package com.symptomgraph.dto;

import lombok.Data;

@Data
public class CorpusProcessMessage {

    private Long recordId;

    private String captureId;

    private String imageHash;

    private String ossBucket;

    private String ossObjectKey;

    private String mimeType;

    private boolean force;
}
