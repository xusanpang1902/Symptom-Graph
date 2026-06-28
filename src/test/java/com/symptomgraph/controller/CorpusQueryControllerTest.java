package com.symptomgraph.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.symptomgraph.entity.CaptureRecord;
import com.symptomgraph.entity.CorpusRecord;
import com.symptomgraph.dto.CorpusQueryPage;
import com.symptomgraph.dto.CorpusReviewRequest;
import com.symptomgraph.service.CaptureRecordService;
import com.symptomgraph.service.CorpusRecordService;
import com.symptomgraph.service.OssStorageService;
import org.springframework.http.MediaType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CorpusQueryController.class)
class CorpusQueryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CaptureRecordService captureRecordService;

    @MockBean
    private CorpusRecordService corpusRecordService;

    @MockBean
    private OssStorageService ossStorageService;

    @Test
    void searchReturnsPagedResearchFieldsWithoutSignedUrl() throws Exception {
        CorpusRecord record = buildRecord(1L, 1);
        record.setCollectedTime(LocalDateTime.of(2026, 6, 26, 10, 15, 30));
        when(corpusRecordService.search(any())).thenReturn(new CorpusQueryPage(
                1, 20, 1, 1, List.of(record)
        ));

        mockMvc.perform(get("/api/v1/corpus")
                        .param("keyword", "检测")
                        .param("searchFields", "rawContent")
                        .param("searchFields", "contextTarget"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.records[0].rawContent").value("评论原文"))
                .andExpect(jsonPath("$.records[0].contextTarget").value("上下文原文"))
                .andExpect(jsonPath("$.records[0].collectedTime").value("2026-06-26T10:15:30"))
                .andExpect(jsonPath("$.records[0].imageHash").value("hash_1"))
                .andExpect(jsonPath("$.records[0].reviewStatus").value("UNREVIEWED"))
                .andExpect(jsonPath("$.records[0].signedUrl").doesNotExist())
                .andExpect(jsonPath("$.records[0].modelRawResponse").doesNotExist());
    }

    @Test
    void detailReturnsRecord() throws Exception {
        CorpusRecord record = buildRecord(1L, 1);
        when(corpusRecordService.getById(1L)).thenReturn(record);

        mockMvc.perform(get("/api/v1/corpus/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.platform").value("小红书"))
                .andExpect(jsonPath("$.tags[0]").value("医疗焦虑"))
                .andExpect(jsonPath("$.reviewStatus").value("UNREVIEWED"));
    }

    @Test
    void reviewUpdatesRecordAndReturnsReviewedFields() throws Exception {
        CorpusReviewRequest request = new CorpusReviewRequest();
        request.setReviewStatus("CORRECTED");
        request.setReviewedRawContent("人工修正评论");
        request.setReviewedContextTarget("人工修正上下文");
        request.setReviewedTags(List.of("医疗焦虑"));
        request.setReviewNote("已核对截图");

        CorpusRecord reviewedRecord = buildRecord(1L, 1);
        reviewedRecord.setReviewStatus("CORRECTED");
        reviewedRecord.setReviewedRawContent("人工修正评论");
        reviewedRecord.setReviewedContextTarget("人工修正上下文");
        reviewedRecord.setReviewedTags("[\"医疗焦虑\"]");
        reviewedRecord.setReviewNote("已核对截图");
        when(corpusRecordService.review(eq(1L), any(CorpusReviewRequest.class))).thenReturn(reviewedRecord);

        mockMvc.perform(patch("/api/v1/corpus/1/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("CORRECTED"))
                .andExpect(jsonPath("$.reviewedRawContent").value("人工修正评论"))
                .andExpect(jsonPath("$.reviewedContextTarget").value("人工修正上下文"))
                .andExpect(jsonPath("$.reviewedTags[0]").value("医疗焦虑"))
                .andExpect(jsonPath("$.reviewNote").value("已核对截图"))
                .andExpect(jsonPath("$.rawContent").value("评论原文"));
    }

    @Test
    void detailReturnsNotFound() throws Exception {
        when(corpusRecordService.getById(404L)).thenReturn(null);

        mockMvc.perform(get("/api/v1/corpus/404"))
                .andExpect(status().isNotFound());
    }

    @Test
    void byCaptureReturnsRecords() throws Exception {
        when(corpusRecordService.listByCaptureId("capture_1")).thenReturn(List.of(buildRecord(1L, 1), buildRecord(2L, 2)));

        mockMvc.perform(get("/api/v1/corpus/captures/capture_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].commentIndex").value(1))
                .andExpect(jsonPath("$[1].commentIndex").value(2));
    }

    @Test
    void captureRecordReturnsTaskStatus() throws Exception {
        CaptureRecord record = new CaptureRecord();
        record.setId(10L);
        record.setCaptureId("capture_1");
        record.setImageHash("hash_1");
        record.setProcessStatus("PROCESSING");
        record.setRetryCount(1);
        when(captureRecordService.getById(10L)).thenReturn(record);

        mockMvc.perform(get("/api/v1/corpus/capture-records/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.captureId").value("capture_1"))
                .andExpect(jsonPath("$.processStatus").value("PROCESSING"))
                .andExpect(jsonPath("$.retryCount").value(1));
    }

    @Test
    void imageUrlReturnsSignedUrl() throws Exception {
        CorpusRecord record = buildRecord(1L, 1);
        record.setOssObjectKey("corpus/test.png");
        when(corpusRecordService.getById(1L)).thenReturn(record);
        when(ossStorageService.generateSignedUrl("corpus/test.png")).thenReturn("https://signed.example/test.png");

        mockMvc.perform(get("/api/v1/corpus/1/image-url"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signedUrl").value("https://signed.example/test.png"));
    }

    private CorpusRecord buildRecord(Long id, Integer commentIndex) {
        CorpusRecord record = new CorpusRecord();
        record.setId(id);
        record.setCaptureId("capture_1");
        record.setCommentIndex(commentIndex);
        record.setPlatform("小红书");
        record.setRawContent("评论原文");
        record.setContextTarget("上下文原文");
        record.setImageHash("hash_1");
        record.setTags("[\"医疗焦虑\"]");
        record.setParseStatus("SUCCESS");
        return record;
    }
}
