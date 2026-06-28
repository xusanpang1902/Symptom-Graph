package com.symptomgraph.dto;

import com.symptomgraph.entity.CorpusRecord;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class CorpusQueryRecordResponse {

    private Long id;
    private String captureId;
    private Integer commentIndex;
    private String platform;
    private String rawContent;
    private String contextTarget;
    private List<String> tags;
    private String parseStatus;
    private LocalDateTime collectedTime;
    private String imageHash;
    private String reviewStatus;
    private String reviewedRawContent;
    private String reviewedContextTarget;
    private List<String> reviewedTags;
    private LocalDateTime reviewedAt;
    private String reviewNote;

    public static CorpusQueryRecordResponse from(CorpusRecord record, List<String> tags) {
        return from(record, tags, List.of());
    }

    public static CorpusQueryRecordResponse from(CorpusRecord record, List<String> tags, List<String> reviewedTags) {
        CorpusQueryRecordResponse response = new CorpusQueryRecordResponse();
        response.setId(record.getId());
        response.setCaptureId(record.getCaptureId());
        response.setCommentIndex(record.getCommentIndex());
        response.setPlatform(record.getPlatform());
        response.setRawContent(record.getRawContent());
        response.setContextTarget(record.getContextTarget());
        response.setTags(tags);
        response.setParseStatus(record.getParseStatus());
        response.setCollectedTime(record.getCollectedTime());
        response.setImageHash(record.getImageHash());
        response.setReviewStatus(record.getReviewStatus() == null ? "UNREVIEWED" : record.getReviewStatus());
        response.setReviewedRawContent(record.getReviewedRawContent());
        response.setReviewedContextTarget(record.getReviewedContextTarget());
        response.setReviewedTags(reviewedTags);
        response.setReviewedAt(record.getReviewedAt());
        response.setReviewNote(record.getReviewNote());
        return response;
    }
}
