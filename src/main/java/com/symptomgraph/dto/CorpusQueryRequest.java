package com.symptomgraph.dto;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class CorpusQueryRequest {

    private String platform;
    private String parseStatus;
    private String tag;
    private String captureId;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime collectedFrom;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime collectedTo;

    private String keyword;
    private List<String> searchFields;
    private Integer page;
    private Integer pageSize;
}
