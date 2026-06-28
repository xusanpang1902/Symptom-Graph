package com.symptomgraph.dto;

import lombok.Data;

import java.util.List;

@Data
public class CorpusReviewRequest {

    private String reviewStatus;

    private String reviewedRawContent;

    private String reviewedContextTarget;

    private List<String> reviewedTags;

    private String reviewNote;
}
