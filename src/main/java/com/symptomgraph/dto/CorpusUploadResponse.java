package com.symptomgraph.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CorpusUploadResponse {

    private String captureId;

    private String imageHash;

    private Long captureRecordId;

    private Long recordId;

    private String parseStatus;

    private String provider;

    private String model;

    private boolean duplicate;

    private boolean force;

    private boolean asyncSubmitted;

    private List<CorpusRecordResponse> records = new ArrayList<>();
}
