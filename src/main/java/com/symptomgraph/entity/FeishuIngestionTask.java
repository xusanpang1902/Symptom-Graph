package com.symptomgraph.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("feishu_ingestion_task")
public class FeishuIngestionTask {

    @TableId
    private Long id;

    private String eventId;

    private String messageId;

    private String chatId;

    private String senderId;

    private String imageKey;

    private Long captureRecordId;

    private String captureId;

    private String imageHash;

    private String status;

    private String errorMessage;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
