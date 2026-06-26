package com.symptomgraph.dto;

import lombok.Data;

import java.util.List;

@Data
public class CorpusPageResponse {

    private long page;
    private long pageSize;
    private long total;
    private long totalPages;
    private List<CorpusQueryRecordResponse> records;
}
