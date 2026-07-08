package com.symptomgraph.integration.feishu;

import com.symptomgraph.dto.CorpusUploadResponse;
import com.symptomgraph.entity.FeishuIngestionTask;
import com.symptomgraph.service.CorpusIngestionService;
import com.symptomgraph.service.FeishuIngestionTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class FeishuImageIngestionService {

    private static final Logger log = LoggerFactory.getLogger(FeishuImageIngestionService.class);

    public static final String STATUS_RECEIVED = "RECEIVED";
    public static final String STATUS_SUBMITTED = "SUBMITTED";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_DOWNLOAD_FAILED = "DOWNLOAD_FAILED";
    public static final String STATUS_SUBMIT_FAILED = "SUBMIT_FAILED";

    private final FeishuIngestionTaskService taskService;
    private final FeishuOpenApiClient feishuOpenApiClient;
    private final CorpusIngestionService corpusIngestionService;
    private final FeishuReplyService feishuReplyService;

    public FeishuImageIngestionService(FeishuIngestionTaskService taskService,
                                       FeishuOpenApiClient feishuOpenApiClient,
                                       CorpusIngestionService corpusIngestionService,
                                       FeishuReplyService feishuReplyService) {
        this.taskService = taskService;
        this.feishuOpenApiClient = feishuOpenApiClient;
        this.corpusIngestionService = corpusIngestionService;
        this.feishuReplyService = feishuReplyService;
    }

    @Transactional
    public void ingest(FeishuImageMessageEvent event) {
        FeishuIngestionTask existingTask = findExistingTask(event);
        if (existingTask != null) {
            log.info("Duplicate Feishu image event ignored: eventId={}, messageId={}, imageKey={}",
                    event.eventId(), event.messageId(), event.imageKey());
            return;
        }

        FeishuIngestionTask task = newTask(event);
        taskService.save(task);

        FeishuImageResource imageResource;
        try {
            imageResource = feishuOpenApiClient.downloadImage(event.messageId(), event.imageKey());
        } catch (RuntimeException ex) {
            markFailed(task, STATUS_DOWNLOAD_FAILED, ex.getMessage());
            feishuReplyService.replyDownloadFailed(task, ex.getMessage());
            return;
        }

        try {
            ByteArrayMultipartFile file = new ByteArrayMultipartFile(
                    "file",
                    imageResource.filename(),
                    imageResource.contentType(),
                    imageResource.bytes()
            );
            CorpusUploadResponse response = corpusIngestionService.ingest(file, false, null, null);
            task.setCaptureRecordId(response.getCaptureRecordId());
            task.setCaptureId(response.getCaptureId());
            task.setImageHash(response.getImageHash());
            task.setStatus(response.isAsyncSubmitted() ? STATUS_SUBMITTED : STATUS_COMPLETED);
            task.setUpdatedAt(LocalDateTime.now());
            taskService.updateById(task);
            if (response.isAsyncSubmitted()) {
                feishuReplyService.replySubmitted(task);
            } else {
                feishuReplyService.replyImmediateResult(task, response);
            }
        } catch (RuntimeException ex) {
            markFailed(task, STATUS_SUBMIT_FAILED, ex.getMessage());
            feishuReplyService.replySubmitFailed(task, ex.getMessage());
        }
    }

    private FeishuIngestionTask findExistingTask(FeishuImageMessageEvent event) {
        FeishuIngestionTask byEventId = taskService.getByEventId(event.eventId());
        if (byEventId != null) {
            return byEventId;
        }
        return taskService.getByMessageImage(event.messageId(), event.imageKey());
    }

    private FeishuIngestionTask newTask(FeishuImageMessageEvent event) {
        LocalDateTime now = LocalDateTime.now();
        FeishuIngestionTask task = new FeishuIngestionTask();
        task.setEventId(event.eventId());
        task.setMessageId(event.messageId());
        task.setChatId(event.chatId());
        task.setSenderId(event.senderId());
        task.setImageKey(event.imageKey());
        task.setStatus(STATUS_RECEIVED);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        return task;
    }

    private void markFailed(FeishuIngestionTask task, String status, String errorMessage) {
        task.setStatus(status);
        task.setErrorMessage(errorMessage);
        task.setUpdatedAt(LocalDateTime.now());
        taskService.updateById(task);
    }
}
