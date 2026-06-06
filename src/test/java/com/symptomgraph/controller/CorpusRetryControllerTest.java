package com.symptomgraph.controller;

import com.symptomgraph.dto.CorpusProcessMessage;
import com.symptomgraph.entity.CaptureRecord;
import com.symptomgraph.entity.CorpusRecord;
import com.symptomgraph.mq.CorpusProcessMessageProducer;
import com.symptomgraph.service.CaptureRecordService;
import com.symptomgraph.service.CorpusRecordService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CorpusRetryController.class)
class CorpusRetryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CaptureRecordService captureRecordService;

    @MockBean
    private CorpusRecordService corpusRecordService;

    @MockBean
    private CorpusProcessMessageProducer corpusProcessMessageProducer;

    @Test
    void retryResetsFailedRecordAndPublishesMessage() throws Exception {
        CorpusRecord record = buildRecord("MODEL_FAILED");
        record.setRetryCount(3);
        record.setLastErrorType("MODEL_FAILED");
        record.setErrorMessage("model failed");
        when(corpusRecordService.getById(1L)).thenReturn(record);

        mockMvc.perform(post("/api/v1/corpus/1/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.parseStatus").value("PROCESSING"));

        ArgumentCaptor<CorpusRecord> recordCaptor = ArgumentCaptor.forClass(CorpusRecord.class);
        verify(corpusRecordService).updateById(recordCaptor.capture());
        assertThat(recordCaptor.getValue().getParseStatus()).isEqualTo("PROCESSING");
        assertThat(recordCaptor.getValue().getRetryCount()).isZero();
        assertThat(recordCaptor.getValue().getErrorMessage()).isNull();
        assertThat(recordCaptor.getValue().getLastErrorType()).isNull();

        ArgumentCaptor<CorpusProcessMessage> messageCaptor = ArgumentCaptor.forClass(CorpusProcessMessage.class);
        verify(corpusProcessMessageProducer).send(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getRecordId()).isEqualTo(1L);
        assertThat(messageCaptor.getValue().getCaptureId()).isEqualTo("capture_1");
        assertThat(messageCaptor.getValue().getMimeType()).isEqualTo("image/png");
        assertThat(messageCaptor.getValue().getRetryCount()).isZero();
    }

    @Test
    void retryReturnsNotFoundWhenRecordMissing() throws Exception {
        when(corpusRecordService.getById(404L)).thenReturn(null);

        mockMvc.perform(post("/api/v1/corpus/404/retry"))
                .andExpect(status().isNotFound());

        verify(corpusProcessMessageProducer, never()).send(any(CorpusProcessMessage.class));
    }

    @Test
    void retryReturnsBadRequestForSuccessRecord() throws Exception {
        when(corpusRecordService.getById(1L)).thenReturn(buildRecord("SUCCESS"));

        mockMvc.perform(post("/api/v1/corpus/1/retry"))
                .andExpect(status().isBadRequest());

        verify(corpusProcessMessageProducer, never()).send(any(CorpusProcessMessage.class));
    }

    @Test
    void retryCaptureRecordResetsFailedTaskAndPublishesMessage() throws Exception {
        CaptureRecord record = buildCaptureRecord("MODEL_FAILED");
        record.setRetryCount(3);
        record.setLastErrorType("MODEL_FAILED");
        record.setErrorMessage("model failed");
        when(captureRecordService.getById(10L)).thenReturn(record);

        mockMvc.perform(post("/api/v1/corpus/capture-records/10/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.processStatus").value("PROCESSING"));

        ArgumentCaptor<CaptureRecord> recordCaptor = ArgumentCaptor.forClass(CaptureRecord.class);
        verify(captureRecordService).updateById(recordCaptor.capture());
        assertThat(recordCaptor.getValue().getProcessStatus()).isEqualTo("PROCESSING");
        assertThat(recordCaptor.getValue().getRetryCount()).isZero();
        assertThat(recordCaptor.getValue().getErrorMessage()).isNull();

        ArgumentCaptor<CorpusProcessMessage> messageCaptor = ArgumentCaptor.forClass(CorpusProcessMessage.class);
        verify(corpusProcessMessageProducer).send(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getCaptureRecordId()).isEqualTo(10L);
        assertThat(messageCaptor.getValue().getRecordId()).isNull();
        assertThat(messageCaptor.getValue().getCaptureId()).isEqualTo("capture_1");
        assertThat(messageCaptor.getValue().getMimeType()).isEqualTo("image/png");
    }

    private CorpusRecord buildRecord(String parseStatus) {
        CorpusRecord record = new CorpusRecord();
        record.setId(1L);
        record.setCaptureId("capture_1");
        record.setCommentIndex(1);
        record.setImageHash("hash_1");
        record.setOssBucket("bucket");
        record.setOssObjectKey("corpus/test.png");
        record.setTags("[]");
        record.setParseStatus(parseStatus);
        return record;
    }

    private CaptureRecord buildCaptureRecord(String processStatus) {
        CaptureRecord record = new CaptureRecord();
        record.setId(10L);
        record.setCaptureId("capture_1");
        record.setImageHash("hash_1");
        record.setOssBucket("bucket");
        record.setOssObjectKey("corpus/test.png");
        record.setMimeType("image/png");
        record.setProcessStatus(processStatus);
        return record;
    }
}
