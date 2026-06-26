package com.symptomgraph.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("capture_record")
public class CaptureRecord {

    @TableId
    private Long id;

    private String captureId;

    private String imageHash;

    private String ossBucket;

    private String ossObjectKey;

    private String mimeType;

    private String provider;

    private String model;

    private String processStatus;

    private Integer retryCount;

    private String lastErrorType;

    private LocalDateTime lastFailedAt;

    private String errorMessage;

    private String modelRawResponse;

    private Boolean duplicate;

    @TableField("`force`")
    private Boolean force;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
