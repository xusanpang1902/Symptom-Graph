package com.symptomgraph.dto;

import com.symptomgraph.entity.CorpusRecord;

import java.util.List;

public record CorpusQueryPage(long page,
                              long pageSize,
                              long total,
                              long totalPages,
                              List<CorpusRecord> records) {
}
