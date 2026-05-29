package com.symptomgraph.controller;

import com.symptomgraph.dto.CorpusRecordResponse;
import com.symptomgraph.dto.CorpusUploadResponse;
import com.symptomgraph.entity.CorpusRecord;
import com.symptomgraph.service.CorpusIngestionService;
import com.symptomgraph.service.CorpusRecordService;
import com.symptomgraph.service.OssStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(CorpusPageController.class)
class CorpusPageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CorpusIngestionService corpusIngestionService;

    @MockBean
    private CorpusRecordService corpusRecordService;

    @MockBean
    private OssStorageService ossStorageService;

    @Test
    void uploadFormReturnsCorpusUploadTemplate() throws Exception {
        mockMvc.perform(get("/corpus/upload"))
                .andExpect(status().isOk())
                .andExpect(view().name("corpus-upload"))
                .andExpect(content().string(containsString("Symptom-Graph 语料采集")))
                .andExpect(content().string(containsString("强制重新识别已有截图")));
    }

    @Test
    void uploadPostsToIngestionServiceAndShowsResult() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.png", "image/png", "image".getBytes());
        CorpusUploadResponse response = buildResponse(false, true);
        CorpusRecord record = new CorpusRecord();
        record.setOssObjectKey("corpus/test.png");

        when(corpusIngestionService.ingest(any(), eq(true))).thenReturn(response);
        when(corpusRecordService.listByCaptureId("capture_1")).thenReturn(List.of(record));
        when(ossStorageService.generateSignedUrl("corpus/test.png")).thenReturn("https://signed.example/test.png");

        mockMvc.perform(multipart("/corpus/upload")
                        .file(file)
                        .param("force", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name("corpus-upload"))
                .andExpect(model().attributeExists("result"))
                .andExpect(model().attribute("signedUrl", "https://signed.example/test.png"))
                .andExpect(content().string(containsString("已强制重新识别")))
                .andExpect(content().string(containsString("评论原文")))
                .andExpect(content().string(containsString("obsidian-output/test.md")));

        verify(corpusIngestionService).ingest(any(), eq(true));
        verify(ossStorageService).generateSignedUrl("corpus/test.png");
    }

    @Test
    void uploadShowsDuplicateNotice() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.png", "image/png", "image".getBytes());
        CorpusUploadResponse response = buildResponse(true, false);

        when(corpusIngestionService.ingest(any(), eq(false))).thenReturn(response);
        when(corpusRecordService.listByCaptureId("capture_1")).thenReturn(List.of());

        mockMvc.perform(multipart("/corpus/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(view().name("corpus-upload"))
                .andExpect(content().string(containsString("该截图已存在，已返回历史识别结果")));
    }

    private CorpusUploadResponse buildResponse(boolean duplicate, boolean force) {
        CorpusRecordResponse record = new CorpusRecordResponse();
        record.setId(1L);
        record.setCaptureId("capture_1");
        record.setCommentIndex(1);
        record.setPlatform("小红书");
        record.setParseStatus("SUCCESS");
        record.setRawContent("评论原文");
        record.setContextTarget("上下文原文");
        record.setTags(List.of("医疗焦虑"));
        record.setMarkdownPath("obsidian-output/test.md");

        CorpusUploadResponse response = new CorpusUploadResponse();
        response.setCaptureId("capture_1");
        response.setImageHash("hash_1");
        response.setDuplicate(duplicate);
        response.setForce(force);
        response.setRecords(List.of(record));
        return response;
    }
}
