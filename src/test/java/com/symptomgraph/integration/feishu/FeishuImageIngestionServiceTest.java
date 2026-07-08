package com.symptomgraph.integration.feishu;

import com.symptomgraph.dto.CorpusUploadResponse;
import com.symptomgraph.entity.FeishuIngestionTask;
import com.symptomgraph.service.CorpusIngestionService;
import com.symptomgraph.service.FeishuIngestionTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeishuImageIngestionServiceTest {

    @Mock
    private FeishuIngestionTaskService taskService;

    @Mock
    private FeishuOpenApiClient feishuOpenApiClient;

    @Mock
    private CorpusIngestionService corpusIngestionService;

    @Mock
    private FeishuReplyService feishuReplyService;

    private FeishuImageIngestionService service;

    @BeforeEach
    void setUp() {
        service = new FeishuImageIngestionService(
                taskService,
                feishuOpenApiClient,
                corpusIngestionService,
                feishuReplyService
        );
    }

    @Test
    void ingestDownloadsImageAndSubmitsCorpusIngestion() {
        FeishuImageMessageEvent event = new FeishuImageMessageEvent("event_1", "om_1", "oc_1", "ou_1", "img_1");
        when(taskService.getByEventId("event_1")).thenReturn(null);
        when(taskService.getByMessageImage("om_1", "img_1")).thenReturn(null);
        when(feishuOpenApiClient.downloadImage("om_1", "img_1"))
                .thenReturn(new FeishuImageResource("image".getBytes(), "image/png", "img_1.png"));
        CorpusUploadResponse response = new CorpusUploadResponse();
        response.setCaptureRecordId(100L);
        response.setCaptureId("capture_1");
        response.setImageHash("hash_1");
        response.setParseStatus("PROCESSING");
        response.setAsyncSubmitted(true);
        when(corpusIngestionService.ingest(any(MultipartFile.class), eq(false), eq(null), eq(null))).thenReturn(response);

        service.ingest(event);

        ArgumentCaptor<FeishuIngestionTask> savedTaskCaptor = ArgumentCaptor.forClass(FeishuIngestionTask.class);
        verify(taskService).save(savedTaskCaptor.capture());
        assertThat(savedTaskCaptor.getValue().getEventId()).isEqualTo("event_1");
        assertThat(savedTaskCaptor.getValue().getMessageId()).isEqualTo("om_1");
        assertThat(savedTaskCaptor.getValue().getImageKey()).isEqualTo("img_1");

        ArgumentCaptor<MultipartFile> fileCaptor = ArgumentCaptor.forClass(MultipartFile.class);
        verify(corpusIngestionService).ingest(fileCaptor.capture(), eq(false), eq(null), eq(null));
        assertThat(fileCaptor.getValue().getOriginalFilename()).isEqualTo("img_1.png");
        assertThat(fileCaptor.getValue().getContentType()).isEqualTo("image/png");

        ArgumentCaptor<FeishuIngestionTask> updatedTaskCaptor = ArgumentCaptor.forClass(FeishuIngestionTask.class);
        verify(taskService).updateById(updatedTaskCaptor.capture());
        assertThat(updatedTaskCaptor.getValue().getCaptureRecordId()).isEqualTo(100L);
        assertThat(updatedTaskCaptor.getValue().getCaptureId()).isEqualTo("capture_1");
        assertThat(updatedTaskCaptor.getValue().getStatus()).isEqualTo(FeishuImageIngestionService.STATUS_SUBMITTED);
        verify(feishuReplyService).replySubmitted(updatedTaskCaptor.getValue());
    }

    @Test
    void ingestSkipsDuplicateEvent() {
        FeishuImageMessageEvent event = new FeishuImageMessageEvent("event_1", "om_1", "oc_1", "ou_1", "img_1");
        when(taskService.getByEventId("event_1")).thenReturn(new FeishuIngestionTask());

        service.ingest(event);

        verify(feishuOpenApiClient, never()).downloadImage(any(), any());
        verify(corpusIngestionService, never()).ingest(any(), eq(false), eq(null), eq(null));
    }
}
