package com.symptomgraph.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("corpus_record")
public class CorpusRecord {

    @TableId
    private Long id;

    private String captureId;

    private Integer commentIndex;

    private String rawContent;

    private String contextTarget;

    private String platform;

    private LocalDateTime originalPublishTime;

    private LocalDateTime collectedTime;

    private String ossBucket;

    private String ossObjectKey;

    private String imageHash;

    private String tags;

    private String modelRawResponse;

    private String parseStatus;

    private String errorMessage;

    private String markdownPath;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
