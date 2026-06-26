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

    public static CorpusQueryRecordResponse from(CorpusRecord record, List<String> tags) {
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
        return response;
    }
}
