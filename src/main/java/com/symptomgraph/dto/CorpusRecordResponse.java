package com.symptomgraph.dto;

import com.symptomgraph.entity.CorpusRecord;
import lombok.Data;

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

    public static CorpusRecordResponse from(CorpusRecord record, List<String> tags) {
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
        return response;
    }
}
