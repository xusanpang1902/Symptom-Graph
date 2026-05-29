package com.symptomgraph.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CorpusUploadResponse {

    private String captureId;

    private String imageHash;

    private boolean duplicate;

    private boolean force;

    private List<CorpusRecordResponse> records = new ArrayList<>();
}
