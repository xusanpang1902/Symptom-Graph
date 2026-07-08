package com.symptomgraph.integration.feishu;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.symptomgraph.config.FeishuProperties;
import com.symptomgraph.dto.CorpusRecordResponse;
import com.symptomgraph.dto.CorpusUploadResponse;
import com.symptomgraph.entity.CorpusRecord;
import com.symptomgraph.entity.FeishuIngestionTask;
import com.symptomgraph.event.CaptureProcessingCompletedEvent;
import com.symptomgraph.service.CorpusRecordService;
import com.symptomgraph.service.FeishuIngestionTaskService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;

@Service
public class FeishuReplyService {

    private final FeishuProperties properties;
    private final FeishuOpenApiClient feishuOpenApiClient;
    private final FeishuIngestionTaskService taskService;
    private final CorpusRecordService corpusRecordService;
    private final ObjectMapper objectMapper;

    public FeishuReplyService(FeishuProperties properties,
                              FeishuOpenApiClient feishuOpenApiClient,
                              FeishuIngestionTaskService taskService,
                              CorpusRecordService corpusRecordService,
                              ObjectMapper objectMapper) {
        this.properties = properties;
        this.feishuOpenApiClient = feishuOpenApiClient;
        this.taskService = taskService;
        this.corpusRecordService = corpusRecordService;
        this.objectMapper = objectMapper;
    }

    public void replySubmitted(FeishuIngestionTask task) {
        send(task, """
                已受理截图采集任务
                captureRecordId: %s
                captureId: %s
                当前状态: PROCESSING
                """.formatted(task.getCaptureRecordId(), task.getCaptureId()).trim());
    }

    public void replyImmediateResult(FeishuIngestionTask task, CorpusUploadResponse response) {
        send(task, """
                截图已存在，已返回历史识别结果
                captureId: %s
                状态: %s
                语料条数: %d
                %s
                """.formatted(
                response.getCaptureId(),
                response.getParseStatus(),
                response.getRecords() == null ? 0 : response.getRecords().size(),
                manageLink(response.getCaptureId())
        ).trim());
    }

    public void replyDownloadFailed(FeishuIngestionTask task, String errorMessage) {
        send(task, failureText("图片下载失败", errorMessage));
    }

    public void replySubmitFailed(FeishuIngestionTask task, String errorMessage) {
        send(task, failureText("截图提交失败", errorMessage));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCaptureCompleted(CaptureProcessingCompletedEvent event) {
        if (event.captureRecordId() == null) {
            return;
        }
        List<FeishuIngestionTask> tasks = taskService.listByCaptureRecordId(event.captureRecordId());
        for (FeishuIngestionTask task : tasks) {
            replyCompleted(task, event);
        }
    }

    private void replyCompleted(FeishuIngestionTask task, CaptureProcessingCompletedEvent event) {
        List<CorpusRecordResponse> records = corpusRecordService.listByCaptureId(event.captureId()).stream()
                .map(record -> CorpusRecordResponse.from(record, parseTags(record)))
                .toList();
        String text = """
                截图识别完成
                captureRecordId: %s
                captureId: %s
                状态: %s
                语料条数: %d
                平台: %s
                标签: %s
                %s
                """.formatted(
                event.captureRecordId(),
                event.captureId(),
                event.processStatus(),
                records.size(),
                platformSummary(records),
                tagSummary(records),
                manageLink(event.captureId())
        ).trim();
        send(task, text);
        task.setStatus(FeishuImageIngestionService.STATUS_COMPLETED);
        task.setUpdatedAt(LocalDateTime.now());
        taskService.updateById(task);
    }

    private List<String> parseTags(CorpusRecord record) {
        if (!StringUtils.hasText(record.getTags())) {
            return List.of();
        }
        try {
            return objectMapper.readValue(record.getTags(), new TypeReference<>() {
            });
        } catch (IOException ex) {
            return List.of();
        }
    }

    private String failureText(String title, String errorMessage) {
        return """
                %s
                原因: %s
                """.formatted(title, StringUtils.hasText(errorMessage) ? errorMessage : "unknown").trim();
    }

    private String platformSummary(List<CorpusRecordResponse> records) {
        return records.stream()
                .map(CorpusRecordResponse::getPlatform)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse("-");
    }

    private String tagSummary(List<CorpusRecordResponse> records) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        for (CorpusRecordResponse record : records) {
            if (record.getTags() != null) {
                tags.addAll(record.getTags());
            }
        }
        if (tags.isEmpty()) {
            return "-";
        }
        return String.join(", ", tags.stream().limit(8).toList());
    }

    private String manageLink(String captureId) {
        if (!StringUtils.hasText(properties.getManageBaseUrl()) || !StringUtils.hasText(captureId)) {
            return "";
        }
        return "管理页: " + properties.getManageBaseUrl().replaceAll("/+$", "") + "/corpus/manage?captureId=" + captureId;
    }

    private void send(FeishuIngestionTask task, String text) {
        if (!properties.isEnabled() || !StringUtils.hasText(task.getChatId())) {
            return;
        }
        feishuOpenApiClient.sendTextMessage(task.getChatId(), text);
    }
}
