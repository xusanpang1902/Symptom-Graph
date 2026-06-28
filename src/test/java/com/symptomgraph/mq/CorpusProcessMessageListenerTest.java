package com.symptomgraph.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.symptomgraph.config.CorpusRabbitMqProperties;
import com.symptomgraph.dto.CorpusProcessMessage;
import com.symptomgraph.dto.VisionRecognitionItem;
import com.symptomgraph.dto.VisionRecognitionOptions;
import com.symptomgraph.dto.VisionRecognitionResult;
import com.symptomgraph.entity.CaptureRecord;
import com.symptomgraph.entity.CorpusRecord;
import com.symptomgraph.exception.VisionRecognitionException;
import com.symptomgraph.service.CaptureRecordService;
import com.symptomgraph.service.CorpusRecordService;
import com.symptomgraph.service.MarkdownExportService;
import com.symptomgraph.service.OssStorageService;
import com.symptomgraph.service.RecognitionRunService;
import com.symptomgraph.service.VisionRecognitionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CorpusProcessMessageListenerTest {

    @Mock
    private CaptureRecordService captureRecordService;

    @Mock
    private CorpusRecordService corpusRecordService;

    @Mock
    private OssStorageService ossStorageService;

    @Mock
    private RecognitionRunService recognitionRunService;

    @Mock
    private VisionRecognitionService visionRecognitionService;

    @Mock
    private MarkdownExportService markdownExportService;

    @Mock
    private CorpusProcessMessageProducer corpusProcessMessageProducer;

    private CorpusProcessMessageListener listener;

    @BeforeEach
    void setUp() {
        listener = new CorpusProcessMessageListener(
                captureRecordService,
                corpusRecordService,
                ossStorageService,
                recognitionRunService,
                visionRecognitionService,
                markdownExportService,
                corpusProcessMessageProducer,
                new CorpusProcessFailureClassifier(),
                new CorpusRabbitMqProperties(),
                new ObjectMapper()
        );
    }

    @Test
    void handleUpdatesProcessingRecordAndAddsAdditionalRecordsWhenRecognitionSucceeds() {
        CorpusRecord processingRecord = processingRecord();
        CaptureRecord captureRecord = captureRecord();
        CorpusProcessMessage message = message();
        VisionRecognitionResult result = new VisionRecognitionResult();
        result.setPlatform("小红书");
        result.setContextTarget("上下文原文");
        result.setModelRawResponse("{\"ok\":true}");
        VisionRecognitionItem first = new VisionRecognitionItem();
        first.setCommentIndex(1);
        first.setRawContent("第一条评论");
        first.setTags(List.of("#医疗焦虑", "医疗焦虑", "＃恐艾"));
        VisionRecognitionItem second = new VisionRecognitionItem();
        second.setCommentIndex(2);
        second.setRawContent("第二条评论");
        second.setTags(List.of("经验分享"));
        result.setItems(List.of(first, second));

        when(corpusRecordService.getById(10L)).thenReturn(processingRecord);
        when(captureRecordService.getById(100L)).thenReturn(captureRecord);
        when(ossStorageService.download("corpus/test.png")).thenReturn("image".getBytes());
        when(visionRecognitionService.recognize(any(byte[].class), eq("image/png"), any(VisionRecognitionOptions.class))).thenReturn(result);
        when(corpusRecordService.saveBatch(any(Collection.class))).thenAnswer(invocation -> {
            Collection<CorpusRecord> records = invocation.getArgument(0);
            records.forEach(record -> record.setId(20L));
            return true;
        });
        when(markdownExportService.export(any(CorpusRecord.class))).thenReturn("obsidian-output/test.md");

        listener.handle(message);

        ArgumentCaptor<Collection<CorpusRecord>> saveBatchCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(corpusRecordService).saveBatch(saveBatchCaptor.capture());
        assertThat(saveBatchCaptor.getValue())
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.getCommentIndex()).isEqualTo(2);
                    assertThat(record.getRawContent()).isEqualTo("第二条评论");
                    assertThat(record.getTags()).isEqualTo("[\"经验分享\"]");
                });

        ArgumentCaptor<Collection<CorpusRecord>> updateBatchCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(corpusRecordService).updateBatchById(updateBatchCaptor.capture());
        assertThat(updateBatchCaptor.getValue()).hasSize(2);
        CorpusRecord updatedFirst = updateBatchCaptor.getValue().stream()
                .filter(record -> Long.valueOf(10L).equals(record.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(updatedFirst.getParseStatus()).isEqualTo("SUCCESS");
        assertThat(updatedFirst.getRawContent()).isEqualTo("第一条评论");
        assertThat(updatedFirst.getTags()).isEqualTo("[\"医疗焦虑\",\"恐艾\"]");
        assertThat(updatedFirst.getMarkdownPath()).isEqualTo("obsidian-output/test.md");
        verify(markdownExportService).export(updatedFirst);

        ArgumentCaptor<CaptureRecord> captureRecordCaptor = ArgumentCaptor.forClass(CaptureRecord.class);
        verify(captureRecordService).updateById(captureRecordCaptor.capture());
        assertThat(captureRecordCaptor.getValue().getProcessStatus()).isEqualTo("SUCCESS");
        assertThat(captureRecordCaptor.getValue().getModelRawResponse()).isEqualTo("{\"ok\":true}");
        assertThat(captureRecordCaptor.getValue().getRetryCount()).isZero();
        assertThat(captureRecordCaptor.getValue().getLastErrorType()).isNull();
    }

    @Test
    void handleSavesCorpusRecordsFromCaptureRecordWhenNoPlaceholderExists() {
        CaptureRecord captureRecord = captureRecord();
        CorpusProcessMessage message = message();
        message.setRecordId(null);
        VisionRecognitionResult result = new VisionRecognitionResult();
        result.setPlatform("小红书");
        result.setContextTarget("上下文原文");
        result.setModelRawResponse("{\"ok\":true}");
        VisionRecognitionItem item = new VisionRecognitionItem();
        item.setCommentIndex(1);
        item.setRawContent("第一条评论");
        item.setTags(List.of("医疗焦虑"));
        result.setItems(List.of(item));

        when(captureRecordService.getById(100L)).thenReturn(captureRecord);
        when(ossStorageService.download("corpus/test.png")).thenReturn("image".getBytes());
        when(visionRecognitionService.recognize(any(byte[].class), eq("image/png"), any(VisionRecognitionOptions.class))).thenReturn(result);
        when(corpusRecordService.saveBatch(any(Collection.class))).thenAnswer(invocation -> {
            Collection<CorpusRecord> records = invocation.getArgument(0);
            records.forEach(record -> record.setId(30L));
            return true;
        });
        when(markdownExportService.export(any(CorpusRecord.class))).thenReturn("obsidian-output/test.md");

        listener.handle(message);

        ArgumentCaptor<Collection<CorpusRecord>> saveBatchCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(corpusRecordService).saveBatch(saveBatchCaptor.capture());
        assertThat(saveBatchCaptor.getValue())
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.getCaptureId()).isEqualTo("capture_1");
                    assertThat(record.getRawContent()).isEqualTo("第一条评论");
                    assertThat(record.getParseStatus()).isEqualTo("SUCCESS");
                });

        ArgumentCaptor<Collection<CorpusRecord>> updateBatchCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(corpusRecordService).updateBatchById(updateBatchCaptor.capture());
        assertThat(updateBatchCaptor.getValue()).singleElement()
                .satisfies(record -> assertThat(record.getMarkdownPath()).isEqualTo("obsidian-output/test.md"));
        ArgumentCaptor<CaptureRecord> captureRecordCaptor = ArgumentCaptor.forClass(CaptureRecord.class);
        verify(captureRecordService).updateById(captureRecordCaptor.capture());
        assertThat(captureRecordCaptor.getValue().getProcessStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void handleMarksCaptureEmptyWithoutSavingCorpusRecordWhenNoItemsAndNoPlaceholder() {
        CaptureRecord captureRecord = captureRecord();
        CorpusProcessMessage message = message();
        message.setRecordId(null);
        VisionRecognitionResult result = new VisionRecognitionResult();
        result.setItems(List.of());
        result.setModelRawResponse("{\"ok\":true}");

        when(captureRecordService.getById(100L)).thenReturn(captureRecord);
        when(ossStorageService.download("corpus/test.png")).thenReturn("image".getBytes());
        when(visionRecognitionService.recognize(any(byte[].class), eq("image/png"), any(VisionRecognitionOptions.class))).thenReturn(result);

        listener.handle(message);

        verify(corpusRecordService, never()).saveBatch(any(Collection.class));
        verify(corpusRecordService, never()).updateBatchById(any(Collection.class));
        ArgumentCaptor<CaptureRecord> captureRecordCaptor = ArgumentCaptor.forClass(CaptureRecord.class);
        verify(captureRecordService).updateById(captureRecordCaptor.capture());
        assertThat(captureRecordCaptor.getValue().getProcessStatus()).isEqualTo("EMPTY_RESULT");
        assertThat(captureRecordCaptor.getValue().getErrorMessage()).contains("no visible comment items");
    }

    @Test
    void handleMarksEmptyResultWhenRecognitionReturnsNoItems() {
        CorpusRecord processingRecord = processingRecord();
        CaptureRecord captureRecord = captureRecord();
        CorpusProcessMessage message = message();
        VisionRecognitionResult result = new VisionRecognitionResult();
        result.setItems(List.of());
        result.setModelRawResponse("{\"ok\":true}");

        when(corpusRecordService.getById(10L)).thenReturn(processingRecord);
        when(captureRecordService.getById(100L)).thenReturn(captureRecord);
        when(ossStorageService.download("corpus/test.png")).thenReturn("image".getBytes());
        when(visionRecognitionService.recognize(any(byte[].class), eq("image/png"), any(VisionRecognitionOptions.class))).thenReturn(result);

        listener.handle(message);

        ArgumentCaptor<Collection<CorpusRecord>> updateBatchCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(corpusRecordService).updateBatchById(updateBatchCaptor.capture());
        assertThat(updateBatchCaptor.getValue())
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.getId()).isEqualTo(10L);
                    assertThat(record.getParseStatus()).isEqualTo("EMPTY_RESULT");
                    assertThat(record.getErrorMessage()).contains("no visible comment items");
                });
        verify(corpusRecordService, never()).saveBatch(any(Collection.class));
        verify(markdownExportService, never()).export(any(CorpusRecord.class));

        ArgumentCaptor<CaptureRecord> captureRecordCaptor = ArgumentCaptor.forClass(CaptureRecord.class);
        verify(captureRecordService).updateById(captureRecordCaptor.capture());
        assertThat(captureRecordCaptor.getValue().getProcessStatus()).isEqualTo("EMPTY_RESULT");
        assertThat(captureRecordCaptor.getValue().getErrorMessage()).contains("no visible comment items");
    }

    @Test
    void handleSchedulesRetryWhenModelFailureIsRetryable() {
        CorpusRecord processingRecord = processingRecord();
        CaptureRecord captureRecord = captureRecord();
        CorpusProcessMessage message = message();

        when(corpusRecordService.getById(10L)).thenReturn(processingRecord);
        when(captureRecordService.getById(100L)).thenReturn(captureRecord);
        when(ossStorageService.download("corpus/test.png")).thenReturn("image".getBytes());
        when(visionRecognitionService.recognize(any(byte[].class), eq("image/png"), any(VisionRecognitionOptions.class)))
                .thenThrow(new VisionRecognitionException("MODEL_FAILED", "model failed", "{\"error\":true}", null));

        listener.handle(message);

        ArgumentCaptor<CorpusRecord> recordCaptor = ArgumentCaptor.forClass(CorpusRecord.class);
        verify(corpusRecordService).updateById(recordCaptor.capture());
        assertThat(recordCaptor.getValue().getParseStatus()).isEqualTo("PROCESSING");
        assertThat(recordCaptor.getValue().getErrorMessage()).isEqualTo("model failed");
        assertThat(recordCaptor.getValue().getModelRawResponse()).isEqualTo("{\"error\":true}");
        assertThat(recordCaptor.getValue().getRetryCount()).isEqualTo(1);
        assertThat(recordCaptor.getValue().getLastErrorType()).isEqualTo("MODEL_FAILED");
        ArgumentCaptor<CaptureRecord> captureRecordCaptor = ArgumentCaptor.forClass(CaptureRecord.class);
        verify(captureRecordService).updateById(captureRecordCaptor.capture());
        assertThat(captureRecordCaptor.getValue().getProcessStatus()).isEqualTo("PROCESSING");
        assertThat(captureRecordCaptor.getValue().getErrorMessage()).isEqualTo("model failed");
        assertThat(captureRecordCaptor.getValue().getModelRawResponse()).isEqualTo("{\"error\":true}");
        assertThat(captureRecordCaptor.getValue().getRetryCount()).isEqualTo(1);
        assertThat(captureRecordCaptor.getValue().getLastErrorType()).isEqualTo("MODEL_FAILED");
        ArgumentCaptor<CorpusProcessMessage> messageCaptor = ArgumentCaptor.forClass(CorpusProcessMessage.class);
        verify(corpusProcessMessageProducer).sendRetry(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getRetryCount()).isEqualTo(1);
        assertThat(messageCaptor.getValue().getLastErrorType()).isEqualTo("MODEL_FAILED");
        verify(corpusProcessMessageProducer, never()).sendDeadLetter(any(CorpusProcessMessage.class));
        verify(markdownExportService, never()).export(any(CorpusRecord.class));
    }

    @Test
    void handleMarksFinalFailureAndSendsDeadLetterWhenRetryLimitReached() {
        CorpusRecord processingRecord = processingRecord();
        CaptureRecord captureRecord = captureRecord();
        CorpusProcessMessage message = message();
        message.setRetryCount(3);

        when(corpusRecordService.getById(10L)).thenReturn(processingRecord);
        when(captureRecordService.getById(100L)).thenReturn(captureRecord);
        when(ossStorageService.download("corpus/test.png")).thenReturn("image".getBytes());
        when(visionRecognitionService.recognize(any(byte[].class), eq("image/png"), any(VisionRecognitionOptions.class)))
                .thenThrow(new VisionRecognitionException("MODEL_FAILED", "model failed", "{\"error\":true}", null));

        listener.handle(message);

        ArgumentCaptor<CorpusRecord> recordCaptor = ArgumentCaptor.forClass(CorpusRecord.class);
        verify(corpusRecordService).updateById(recordCaptor.capture());
        assertThat(recordCaptor.getValue().getParseStatus()).isEqualTo("MODEL_FAILED");
        assertThat(recordCaptor.getValue().getRetryCount()).isEqualTo(4);
        assertThat(recordCaptor.getValue().getLastErrorType()).isEqualTo("MODEL_FAILED");
        ArgumentCaptor<CaptureRecord> captureRecordCaptor = ArgumentCaptor.forClass(CaptureRecord.class);
        verify(captureRecordService).updateById(captureRecordCaptor.capture());
        assertThat(captureRecordCaptor.getValue().getProcessStatus()).isEqualTo("MODEL_FAILED");
        assertThat(captureRecordCaptor.getValue().getRetryCount()).isEqualTo(4);
        assertThat(captureRecordCaptor.getValue().getLastErrorType()).isEqualTo("MODEL_FAILED");
        verify(corpusProcessMessageProducer, never()).sendRetry(any(CorpusProcessMessage.class));
        verify(corpusProcessMessageProducer).sendDeadLetter(any(CorpusProcessMessage.class));
    }

    @Test
    void handleDoesNotRetryParseFailureAndSendsDeadLetter() {
        CorpusRecord processingRecord = processingRecord();
        CaptureRecord captureRecord = captureRecord();
        CorpusProcessMessage message = message();

        when(corpusRecordService.getById(10L)).thenReturn(processingRecord);
        when(captureRecordService.getById(100L)).thenReturn(captureRecord);
        when(ossStorageService.download("corpus/test.png")).thenReturn("image".getBytes());
        when(visionRecognitionService.recognize(any(byte[].class), eq("image/png"), any(VisionRecognitionOptions.class)))
                .thenThrow(new VisionRecognitionException("PARSE_FAILED", "parse failed", "{\"bad\":true}", null));

        listener.handle(message);

        ArgumentCaptor<CorpusRecord> recordCaptor = ArgumentCaptor.forClass(CorpusRecord.class);
        verify(corpusRecordService).updateById(recordCaptor.capture());
        assertThat(recordCaptor.getValue().getParseStatus()).isEqualTo("PARSE_FAILED");
        assertThat(recordCaptor.getValue().getRetryCount()).isEqualTo(1);
        assertThat(recordCaptor.getValue().getLastErrorType()).isEqualTo("PARSE_FAILED");
        ArgumentCaptor<CaptureRecord> captureRecordCaptor = ArgumentCaptor.forClass(CaptureRecord.class);
        verify(captureRecordService).updateById(captureRecordCaptor.capture());
        assertThat(captureRecordCaptor.getValue().getProcessStatus()).isEqualTo("PARSE_FAILED");
        assertThat(captureRecordCaptor.getValue().getRetryCount()).isEqualTo(1);
        assertThat(captureRecordCaptor.getValue().getLastErrorType()).isEqualTo("PARSE_FAILED");
        verify(corpusProcessMessageProducer, never()).sendRetry(any(CorpusProcessMessage.class));
        verify(corpusProcessMessageProducer).sendDeadLetter(any(CorpusProcessMessage.class));
    }

    private CorpusProcessMessage message() {
        CorpusProcessMessage message = new CorpusProcessMessage();
        message.setCaptureRecordId(100L);
        message.setRecordId(10L);
        message.setCaptureId("capture_1");
        message.setImageHash("hash_1");
        message.setOssBucket("bucket");
        message.setOssObjectKey("corpus/test.png");
        message.setMimeType("image/png");
        return message;
    }

    private CaptureRecord captureRecord() {
        CaptureRecord record = new CaptureRecord();
        record.setId(100L);
        record.setCaptureId("capture_1");
        record.setImageHash("hash_1");
        record.setOssBucket("bucket");
        record.setOssObjectKey("corpus/test.png");
        record.setMimeType("image/png");
        record.setProvider("gemini");
        record.setModel("gemini-2.5-flash");
        record.setProcessStatus("PROCESSING");
        record.setRetryCount(0);
        record.setCreatedAt(LocalDateTime.of(2026, 6, 5, 15, 0));
        record.setUpdatedAt(LocalDateTime.of(2026, 6, 5, 15, 0));
        return record;
    }

    private CorpusRecord processingRecord() {
        CorpusRecord record = new CorpusRecord();
        record.setId(10L);
        record.setCaptureId("capture_1");
        record.setCommentIndex(1);
        record.setCollectedTime(LocalDateTime.of(2026, 6, 5, 15, 0));
        record.setOssBucket("bucket");
        record.setOssObjectKey("corpus/test.png");
        record.setImageHash("hash_1");
        record.setParseStatus("PROCESSING");
        record.setTags("[]");
        record.setCreatedAt(LocalDateTime.of(2026, 6, 5, 15, 0));
        record.setUpdatedAt(LocalDateTime.of(2026, 6, 5, 15, 0));
        return record;
    }
}
