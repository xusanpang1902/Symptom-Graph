package com.symptomgraph.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.symptomgraph.config.GeminiProperties;
import com.symptomgraph.config.OpenRouterProperties;
import com.symptomgraph.config.VisionProperties;
import com.symptomgraph.dto.CorpusProcessMessage;
import com.symptomgraph.dto.CorpusRecordResponse;
import com.symptomgraph.dto.CorpusUploadResponse;
import com.symptomgraph.dto.OssUploadResult;
import com.symptomgraph.dto.VisionRecognitionItem;
import com.symptomgraph.dto.VisionRecognitionOptions;
import com.symptomgraph.dto.VisionRecognitionResult;
import com.symptomgraph.entity.CaptureRecord;
import com.symptomgraph.entity.CorpusRecord;
import com.symptomgraph.service.CaptureRecordService;
import com.symptomgraph.service.CorpusRecordService;
import com.symptomgraph.service.ImageHashBloomFilterService;
import com.symptomgraph.service.MarkdownExportService;
import com.symptomgraph.service.OssStorageService;
import com.symptomgraph.service.RecognitionRunService;
import com.symptomgraph.service.RecognitionTokenUsageParser;
import com.symptomgraph.service.VisionRecognitionService;
import com.symptomgraph.util.ImageHashUtils;
import com.symptomgraph.exception.VisionRecognitionException;
import com.symptomgraph.mq.CorpusProcessMessageProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CorpusIngestionServiceImplTest {

    @Mock
    private CaptureRecordService captureRecordService;

    @Mock
    private CorpusRecordService corpusRecordService;

    @Mock
    private ImageHashBloomFilterService imageHashBloomFilterService;

    @Mock
    private OssStorageService ossStorageService;

    @Mock
    private VisionRecognitionService visionRecognitionService;

    @Mock
    private RecognitionRunService recognitionRunService;

    @Mock
    private MarkdownExportService markdownExportService;

    @Mock
    private CorpusProcessMessageProducer corpusProcessMessageProducer;

    private CorpusIngestionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CorpusIngestionServiceImpl(
                captureRecordService,
                corpusRecordService,
                imageHashBloomFilterService,
                ossStorageService,
                visionRecognitionService,
                recognitionRunService,
                markdownExportService,
                corpusProcessMessageProducer,
                new VisionProperties(),
                new GeminiProperties(),
                new OpenRouterProperties(),
                new ObjectMapper(),
                new RecognitionTokenUsageParser(new ObjectMapper())
        );
    }

    @Test
    void ingestReturnsExistingRecordsWhenDuplicateAndNotForced() {
        byte[] imageBytes = "same-image".getBytes();
        String imageHash = ImageHashUtils.sha256Hex(imageBytes);
        MockMultipartFile file = new MockMultipartFile("file", "test.png", "image/png", imageBytes);
        CorpusRecord existingRecord = new CorpusRecord();
        existingRecord.setId(1L);
        existingRecord.setCaptureId("capture_1");
        existingRecord.setCommentIndex(1);
        existingRecord.setImageHash(imageHash);
        existingRecord.setTags("[\"医疗焦虑\"]");
        existingRecord.setParseStatus("SUCCESS");
        when(imageHashBloomFilterService.mightContain(imageHash)).thenReturn(true);
        when(corpusRecordService.listByImageHash(imageHash)).thenReturn(List.of(existingRecord));

        CorpusUploadResponse response = service.ingest(file, false);

        assertThat(response.isDuplicate()).isTrue();
        assertThat(response.isForce()).isFalse();
        assertThat(response.getCaptureId()).isEqualTo("capture_1");
        assertThat(response.getImageHash()).isEqualTo(imageHash);
        assertThat(response.getRecords()).hasSize(1);
        assertThat(response.getRecords().get(0).getTags()).containsExactly("医疗焦虑");
        verify(ossStorageService, never()).upload(any(), any());
        verify(visionRecognitionService, never()).recognize(any(), any());
    }

    @Test
    void ingestUploadsPersistsCaptureTaskAndPublishesMessageForNewImage() {
        byte[] imageBytes = "new-image".getBytes();
        String imageHash = ImageHashUtils.sha256Hex(imageBytes);
        MockMultipartFile file = new MockMultipartFile("file", "test.png", "image/png", imageBytes);

        when(imageHashBloomFilterService.mightContain(imageHash)).thenReturn(false);
        when(ossStorageService.upload(eq(file), any())).thenReturn(new OssUploadResult("bucket", "corpus/test.png", "test.png", "image/png", imageBytes.length));
        when(captureRecordService.save(any(CaptureRecord.class))).thenAnswer(invocation -> {
            CaptureRecord record = invocation.getArgument(0);
            record.setId(10L);
            return true;
        });

        CorpusUploadResponse response = service.ingest(file, false);

        assertThat(response.isDuplicate()).isFalse();
        assertThat(response.isAsyncSubmitted()).isTrue();
        assertThat(response.getImageHash()).isEqualTo(imageHash);
        assertThat(response.getCaptureRecordId()).isEqualTo(10L);
        assertThat(response.getRecordId()).isNull();
        assertThat(response.getParseStatus()).isEqualTo("PROCESSING");
        assertThat(response.getRecords()).isEmpty();
        ArgumentCaptor<CaptureRecord> captureRecordCaptor = ArgumentCaptor.forClass(CaptureRecord.class);
        verify(captureRecordService).save(captureRecordCaptor.capture());
        assertThat(captureRecordCaptor.getValue().getCaptureId()).isEqualTo(response.getCaptureId());
        assertThat(captureRecordCaptor.getValue().getImageHash()).isEqualTo(imageHash);
        assertThat(captureRecordCaptor.getValue().getProcessStatus()).isEqualTo("PROCESSING");
        assertThat(captureRecordCaptor.getValue().getMimeType()).isEqualTo("image/png");
        assertThat(captureRecordCaptor.getValue().getProvider()).isEqualTo("gemini");
        verify(corpusRecordService, never()).save(any(CorpusRecord.class));
        ArgumentCaptor<CorpusProcessMessage> messageCaptor = ArgumentCaptor.forClass(CorpusProcessMessage.class);
        verify(corpusProcessMessageProducer).send(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getCaptureRecordId()).isEqualTo(10L);
        assertThat(messageCaptor.getValue().getRecordId()).isNull();
        assertThat(messageCaptor.getValue().getCaptureId()).isEqualTo(response.getCaptureId());
        assertThat(messageCaptor.getValue().getImageHash()).isEqualTo(imageHash);
        assertThat(messageCaptor.getValue().getOssObjectKey()).isEqualTo("corpus/test.png");
        assertThat(messageCaptor.getValue().getMimeType()).isEqualTo("image/png");
        verify(visionRecognitionService, never()).recognize(any(), any());
        verify(markdownExportService, never()).export(any(CorpusRecord.class));
        verify(corpusRecordService, never()).updateBatchById(any(Collection.class));
        verify(imageHashBloomFilterService).add(imageHash);
    }

    @Test
    void ingestForceReusesExistingOssObjectAndRemovesOldRecords() {
        byte[] imageBytes = "force-image".getBytes();
        String imageHash = ImageHashUtils.sha256Hex(imageBytes);
        MockMultipartFile file = new MockMultipartFile("file", "test.png", "image/png", imageBytes);
        CorpusRecord existingRecord = new CorpusRecord();
        existingRecord.setCaptureId("capture_1");
        existingRecord.setCommentIndex(1);
        existingRecord.setImageHash(imageHash);
        existingRecord.setOssBucket("bucket");
        existingRecord.setOssObjectKey("corpus/existing.png");
        existingRecord.setCollectedTime(LocalDateTime.now());
        VisionRecognitionResult recognitionResult = new VisionRecognitionResult();
        VisionRecognitionItem item = new VisionRecognitionItem();
        item.setCommentIndex(1);
        item.setRawContent("重新识别评论");
        item.setTags(List.of("医疗焦虑"));
        recognitionResult.setItems(List.of(item));
        recognitionResult.setModelRawResponse("{\"candidates\":[]}");

        when(imageHashBloomFilterService.mightContain(imageHash)).thenReturn(true);
        when(corpusRecordService.listByImageHash(imageHash)).thenReturn(List.of(existingRecord));
        when(corpusRecordService.removeByImageHash(imageHash)).thenReturn(true);
        when(visionRecognitionService.recognize(any(byte[].class), eq("image/png"), any(VisionRecognitionOptions.class))).thenReturn(recognitionResult);
        when(corpusRecordService.saveBatch(any(Collection.class))).thenAnswer(invocation -> {
            Collection<CorpusRecord> records = invocation.getArgument(0);
            records.forEach(record -> record.setId(200L));
            return true;
        });
        when(markdownExportService.export(any(CorpusRecord.class))).thenReturn("obsidian-output/force.md");
        when(corpusRecordService.updateBatchById(any(Collection.class))).thenReturn(true);

        CorpusUploadResponse response = service.ingest(file, true);

        assertThat(response.isForce()).isTrue();
        assertThat(response.isDuplicate()).isFalse();
        assertThat(response.getCaptureId()).isEqualTo("capture_1");
        verify(corpusRecordService).removeByImageHash(imageHash);
        verify(ossStorageService, never()).upload(any(), any());
    }

    @Test
    void ingestForceKeepsExistingRecordsWhenRecognitionFails() {
        byte[] imageBytes = "force-failed-image".getBytes();
        String imageHash = ImageHashUtils.sha256Hex(imageBytes);
        MockMultipartFile file = new MockMultipartFile("file", "test.png", "image/png", imageBytes);
        CorpusRecord existingRecord = new CorpusRecord();
        existingRecord.setCaptureId("capture_1");
        existingRecord.setCommentIndex(1);
        existingRecord.setImageHash(imageHash);
        existingRecord.setOssBucket("bucket");
        existingRecord.setOssObjectKey("corpus/existing.png");

        when(imageHashBloomFilterService.mightContain(imageHash)).thenReturn(true);
        when(corpusRecordService.listByImageHash(imageHash)).thenReturn(List.of(existingRecord));
        when(visionRecognitionService.recognize(any(byte[].class), eq("image/png"), any(VisionRecognitionOptions.class)))
                .thenThrow(new VisionRecognitionException("MODEL_FAILED", "model failed"));

        CorpusUploadResponse response = service.ingest(file, true);

        assertThat(response.isForce()).isTrue();
        assertThat(response.getCaptureId()).isEqualTo("capture_1");
        assertThat(response.getRecords()).singleElement()
                .extracting(CorpusRecordResponse::getParseStatus)
                .isEqualTo("MODEL_FAILED");
        verify(corpusRecordService, never()).removeByImageHash(imageHash);
        verify(corpusRecordService, never()).saveBatch(any(Collection.class));
        verify(markdownExportService, never()).export(any(CorpusRecord.class));
    }

    @Test
    void ingestNewImageWithForceStillSubmitsAsyncProcessingTask() {
        byte[] imageBytes = "new-force-image".getBytes();
        String imageHash = ImageHashUtils.sha256Hex(imageBytes);
        MockMultipartFile file = new MockMultipartFile("file", "test.png", "image/png", imageBytes);

        when(imageHashBloomFilterService.mightContain(imageHash)).thenReturn(false);
        when(ossStorageService.upload(eq(file), any())).thenReturn(new OssUploadResult("bucket", "corpus/test.png", "test.png", "image/png", imageBytes.length));
        when(captureRecordService.save(any(CaptureRecord.class))).thenAnswer(invocation -> {
            CaptureRecord record = invocation.getArgument(0);
            record.setId(30L);
            return true;
        });

        CorpusUploadResponse response = service.ingest(file, true);

        assertThat(response.isForce()).isTrue();
        assertThat(response.isAsyncSubmitted()).isTrue();
        assertThat(response.getCaptureRecordId()).isEqualTo(30L);
        assertThat(response.getRecordId()).isNull();
        assertThat(response.getParseStatus()).isEqualTo("PROCESSING");
        ArgumentCaptor<CorpusProcessMessage> messageCaptor = ArgumentCaptor.forClass(CorpusProcessMessage.class);
        verify(corpusProcessMessageProducer).send(messageCaptor.capture());
        assertThat(messageCaptor.getValue().isForce()).isTrue();
        assertThat(messageCaptor.getValue().getRecordId()).isNull();
        verify(corpusRecordService, never()).save(any(CorpusRecord.class));
        verify(visionRecognitionService, never()).recognize(any(), any());
    }
}
