package com.symptomgraph.controller;

import com.symptomgraph.entity.CorpusRecord;
import com.symptomgraph.service.CorpusRecordService;
import com.symptomgraph.service.OssStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CorpusQueryController.class)
class CorpusQueryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CorpusRecordService corpusRecordService;

    @MockBean
    private OssStorageService ossStorageService;

    @Test
    void detailReturnsRecord() throws Exception {
        CorpusRecord record = buildRecord(1L, 1);
        when(corpusRecordService.getById(1L)).thenReturn(record);

        mockMvc.perform(get("/api/v1/corpus/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.platform").value("小红书"))
                .andExpect(jsonPath("$.tags[0]").value("医疗焦虑"));
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
