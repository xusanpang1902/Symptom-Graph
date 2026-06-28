package com.symptomgraph.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("recognition_run")
public class RecognitionRun {

    @TableId
    private Long id;

    private Long captureRecordId;

    private String captureId;

    private String imageHash;

    private String provider;

    private String model;

    private String status;

    private Integer itemCount;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private Long durationMs;

    private Long inputTokens;

    private Long outputTokens;

    private Long totalTokens;

    private BigDecimal estimatedCost;

    private String errorType;

    private String errorMessage;

    private String modelRawResponse;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
