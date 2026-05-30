package com.symptomgraph.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.symptomgraph.dto.CorpusRecordResponse;
import com.symptomgraph.dto.CorpusUploadResponse;
import com.symptomgraph.dto.OssUploadResult;
import com.symptomgraph.dto.VisionRecognitionItem;
import com.symptomgraph.dto.VisionRecognitionResult;
import com.symptomgraph.entity.CorpusRecord;
import com.symptomgraph.service.CorpusRecordService;
import com.symptomgraph.service.MarkdownExportService;
import com.symptomgraph.service.OssStorageService;
import com.symptomgraph.service.VisionRecognitionService;
import com.symptomgraph.util.ImageHashUtils;
import com.symptomgraph.exception.VisionRecognitionException;
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
    private CorpusRecordService corpusRecordService;

    @Mock
    private OssStorageService ossStorageService;

    @Mock
    private VisionRecognitionService visionRecognitionService;

    @Mock
    private MarkdownExportService markdownExportService;

    private CorpusIngestionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CorpusIngestionServiceImpl(
                corpusRecordService,
                ossStorageService,
                visionRecognitionService,
                markdownExportService,
                new ObjectMapper()
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
    void ingestUploadsRecognizesPersistsAndExportsMarkdownForNewImage() {
        byte[] imageBytes = "new-image".getBytes();
        String imageHash = ImageHashUtils.sha256Hex(imageBytes);
        MockMultipartFile file = new MockMultipartFile("file", "test.png", "image/png", imageBytes);
        VisionRecognitionItem item = new VisionRecognitionItem();
        item.setCommentIndex(1);
        item.setRawContent("评论原文");
        item.setTags(List.of("#医疗焦虑", " 医疗焦虑 ", "＃恐艾", ""));
        VisionRecognitionResult recognitionResult = new VisionRecognitionResult();
        recognitionResult.setPlatform("小红书");
        recognitionResult.setContextTarget("上下文原文");
        recognitionResult.setItems(List.of(item));
        recognitionResult.setModelRawResponse("{\"candidates\":[]}");
        AtomicLong id = new AtomicLong(100L);

        when(corpusRecordService.listByImageHash(imageHash)).thenReturn(List.of());
        when(ossStorageService.upload(eq(file), any())).thenReturn(new OssUploadResult("bucket", "corpus/test.png", "test.png", "image/png", imageBytes.length));
        when(visionRecognitionService.recognize(any(byte[].class), eq("image/png"))).thenReturn(recognitionResult);
        when(corpusRecordService.saveBatch(any(Collection.class))).thenAnswer(invocation -> {
            Collection<CorpusRecord> records = invocation.getArgument(0);
            records.forEach(record -> record.setId(id.getAndIncrement()));
            return true;
        });
        when(markdownExportService.export(any(CorpusRecord.class))).thenReturn("obsidian-output/test.md");
        when(corpusRecordService.updateBatchById(any(Collection.class))).thenReturn(true);

        CorpusUploadResponse response = service.ingest(file, false);

        assertThat(response.isDuplicate()).isFalse();
        assertThat(response.getImageHash()).isEqualTo(imageHash);
        assertThat(response.getRecords()).hasSize(1);
        assertThat(response.getRecords().get(0).getPlatform()).isEqualTo("小红书");
        assertThat(response.getRecords().get(0).getRawContent()).isEqualTo("评论原文");
        assertThat(response.getRecords().get(0).getTags()).containsExactly("医疗焦虑", "恐艾");
        assertThat(response.getRecords().get(0).getMarkdownPath()).isEqualTo("obsidian-output/test.md");
        ArgumentCaptor<Collection<CorpusRecord>> recordsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(corpusRecordService).saveBatch(recordsCaptor.capture());
        assertThat(recordsCaptor.getValue())
                .singleElement()
                .extracting(CorpusRecord::getTags)
                .isEqualTo("[\"医疗焦虑\",\"恐艾\"]");
        verify(markdownExportService).export(any(CorpusRecord.class));
        verify(corpusRecordService).updateBatchById(any(Collection.class));
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

        when(corpusRecordService.listByImageHash(imageHash)).thenReturn(List.of(existingRecord));
        when(corpusRecordService.removeByImageHash(imageHash)).thenReturn(true);
        when(visionRecognitionService.recognize(any(byte[].class), eq("image/png"))).thenReturn(recognitionResult);
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

        when(corpusRecordService.listByImageHash(imageHash)).thenReturn(List.of(existingRecord));
        when(visionRecognitionService.recognize(any(byte[].class), eq("image/png")))
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
    void ingestMarksEmptyRecognitionResultWithoutExportingMarkdown() {
        byte[] imageBytes = "empty-result-image".getBytes();
        String imageHash = ImageHashUtils.sha256Hex(imageBytes);
        MockMultipartFile file = new MockMultipartFile("file", "test.png", "image/png", imageBytes);
        VisionRecognitionResult recognitionResult = new VisionRecognitionResult();
        recognitionResult.setItems(List.of());
        recognitionResult.setModelRawResponse("{\"candidates\":[]}");

        when(corpusRecordService.listByImageHash(imageHash)).thenReturn(List.of());
        when(ossStorageService.upload(eq(file), any())).thenReturn(new OssUploadResult("bucket", "corpus/test.png", "test.png", "image/png", imageBytes.length));
        when(visionRecognitionService.recognize(any(byte[].class), eq("image/png"))).thenReturn(recognitionResult);
        when(corpusRecordService.saveBatch(any(Collection.class))).thenAnswer(invocation -> {
            Collection<CorpusRecord> records = invocation.getArgument(0);
            records.forEach(record -> record.setId(300L));
            return true;
        });
        when(corpusRecordService.updateBatchById(any(Collection.class))).thenReturn(true);

        CorpusUploadResponse response = service.ingest(file, false);

        assertThat(response.getRecords()).singleElement()
                .extracting(CorpusRecordResponse::getParseStatus)
                .isEqualTo("EMPTY_RESULT");
        assertThat(response.getRecords().get(0).getMarkdownPath()).isNull();
        verify(markdownExportService, never()).export(any(CorpusRecord.class));
    }
}
