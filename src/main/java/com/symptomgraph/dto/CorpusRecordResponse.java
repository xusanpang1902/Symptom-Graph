package com.symptomgraph.dto;

import com.symptomgraph.entity.CorpusRecord;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class CorpusRecordResponse {

    private Long id;

    private String captureId;

    private Integer commentIndex;

    private String rawContent;

    private String contextTarget;

    private String platform;

    private String imageHash;

    private List<String> tags;

    private String parseStatus;

    private String errorMessage;

    private String markdownPath;

    private String reviewStatus;

    private String reviewedRawContent;

    private String reviewedContextTarget;

    private List<String> reviewedTags;

    private LocalDateTime reviewedAt;

    private String reviewNote;

    public static CorpusRecordResponse from(CorpusRecord record, List<String> tags) {
        return from(record, tags, List.of());
    }

    public static CorpusRecordResponse from(CorpusRecord record, List<String> tags, List<String> reviewedTags) {
        CorpusRecordResponse response = new CorpusRecordResponse();
        response.setId(record.getId());
        response.setCaptureId(record.getCaptureId());
        response.setCommentIndex(record.getCommentIndex());
        response.setRawContent(record.getRawContent());
        response.setContextTarget(record.getContextTarget());
        response.setPlatform(record.getPlatform());
        response.setImageHash(record.getImageHash());
        response.setTags(tags);
        response.setParseStatus(record.getParseStatus());
        response.setErrorMessage(record.getErrorMessage());
        response.setMarkdownPath(record.getMarkdownPath());
        response.setReviewStatus(record.getReviewStatus() == null ? "UNREVIEWED" : record.getReviewStatus());
        response.setReviewedRawContent(record.getReviewedRawContent());
        response.setReviewedContextTarget(record.getReviewedContextTarget());
        response.setReviewedTags(reviewedTags);
        response.setReviewedAt(record.getReviewedAt());
        response.setReviewNote(record.getReviewNote());
        return response;
    }
}
