package com.symptomgraph.controller;

import com.symptomgraph.config.VisionConfig;
import com.symptomgraph.dto.CorpusRecordResponse;
import com.symptomgraph.dto.CorpusUploadResponse;
import com.symptomgraph.entity.CaptureRecord;
import com.symptomgraph.entity.CorpusRecord;
import com.symptomgraph.service.CaptureRecordService;
import com.symptomgraph.service.CorpusIngestionService;
import com.symptomgraph.service.CorpusRecordService;
import com.symptomgraph.service.OssStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(CorpusPageController.class)
@Import(VisionConfig.class)
class CorpusPageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CorpusIngestionService corpusIngestionService;

    @MockBean
    private CaptureRecordService captureRecordService;

    @MockBean
    private CorpusRecordService corpusRecordService;

    @MockBean
    private OssStorageService ossStorageService;

    @Test
    void uploadFormReturnsCorpusUploadTemplateWithModelControls() throws Exception {
        mockMvc.perform(get("/corpus/upload"))
                .andExpect(status().isOk())
                .andExpect(view().name("corpus-upload"))
                .andExpect(content().string(containsString("Symptom-Graph")))
                .andExpect(content().string(containsString("name=\"provider\"")))
                .andExpect(content().string(containsString("name=\"model\"")))
                .andExpect(content().string(containsString("gemini-1.5-flash")))
                .andExpect(content().string(containsString("qwen")))
                .andExpect(content().string(containsString("qwen3.6-flash")));
    }

    @Test
    void managePageReturnsCorpusManageTemplate() throws Exception {
        mockMvc.perform(get("/corpus/manage"))
                .andExpect(status().isOk())
                .andExpect(view().name("corpus-manage"))
                .andExpect(content().string(containsString("/api/v1/corpus")))
                .andExpect(content().string(containsString("/corpus/analytics")))
                .andExpect(content().string(containsString("searchFields")));
    }

    @Test
    void analyticsPageReturnsCorpusAnalyticsTemplate() throws Exception {
        mockMvc.perform(get("/corpus/analytics"))
                .andExpect(status().isOk())
                .andExpect(view().name("corpus-analytics"))
                .andExpect(content().string(containsString("/api/v1/corpus/analytics")))
                .andExpect(content().string(containsString("platformCounts")))
                .andExpect(content().string(containsString("tagCounts")));
    }

    @Test
    void uploadPostsProviderAndModelToIngestionServiceAndShowsResult() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.png", "image/png", "image".getBytes());
        CorpusUploadResponse response = buildResponse(false, true);
        response.setProvider("openrouter");
        response.setModel("qwen/qwen3.6-flash");
        CorpusRecord record = new CorpusRecord();
        record.setOssObjectKey("corpus/test.png");

        when(corpusIngestionService.ingest(any(), eq(true), eq("openrouter"), eq("qwen/qwen3.6-flash"))).thenReturn(response);
        when(corpusRecordService.listByCaptureId("capture_1")).thenReturn(List.of(record));
        when(ossStorageService.generateSignedUrl("corpus/test.png")).thenReturn("https://signed.example/test.png");

        mockMvc.perform(multipart("/corpus/upload")
                        .file(file)
                        .param("force", "true")
                        .param("provider", "openrouter")
                        .param("model", "qwen/qwen3.6-flash"))
                .andExpect(status().isOk())
                .andExpect(view().name("corpus-upload"))
                .andExpect(model().attributeExists("result"))
                .andExpect(model().attribute("signedUrl", "https://signed.example/test.png"))
                .andExpect(content().string(containsString("openrouter")))
                .andExpect(content().string(containsString("qwen/qwen3.6-flash")))
                .andExpect(content().string(containsString("raw content")))
                .andExpect(content().string(containsString("obsidian-output/test.md")));

        verify(corpusIngestionService).ingest(any(), eq(true), eq("openrouter"), eq("qwen/qwen3.6-flash"));
        verify(ossStorageService).generateSignedUrl("corpus/test.png");
    }

    @Test
    void uploadShowsDuplicateNotice() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.png", "image/png", "image".getBytes());
        CorpusUploadResponse response = buildResponse(true, false);

        when(corpusIngestionService.ingest(any(), eq(false), nullable(String.class), nullable(String.class))).thenReturn(response);
        when(corpusRecordService.listByCaptureId("capture_1")).thenReturn(List.of());

        mockMvc.perform(multipart("/corpus/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(view().name("corpus-upload"))
                .andExpect(content().string(containsString("duplicate")));
    }

    @Test
    void uploadShowsAsyncPollingElementsWhenProcessingSubmitted() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.png", "image/png", "image".getBytes());
        CorpusUploadResponse response = buildResponse(false, false);
        response.setCaptureRecordId(100L);
        response.setParseStatus("PROCESSING");
        response.setAsyncSubmitted(true);
        response.setRecords(List.of());
        CaptureRecord captureRecord = new CaptureRecord();
        captureRecord.setId(100L);
        captureRecord.setOssObjectKey("corpus/test.png");

        when(corpusIngestionService.ingest(any(), eq(false), nullable(String.class), nullable(String.class))).thenReturn(response);
        when(corpusRecordService.listByCaptureId("capture_1")).thenReturn(List.of());
        when(captureRecordService.getById(100L)).thenReturn(captureRecord);
        when(ossStorageService.generateSignedUrl("corpus/test.png")).thenReturn("https://signed.example/test.png");

        mockMvc.perform(multipart("/corpus/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(view().name("corpus-upload"))
                .andExpect(content().string(containsString("data-capture-id=\"capture_1\"")))
                .andExpect(content().string(containsString("data-capture-record-id=\"100\"")))
                .andExpect(content().string(containsString("data-async-submitted=\"true\"")))
                .andExpect(content().string(containsString("/api/v1/corpus/capture-records/")))
                .andExpect(content().string(containsString("/api/v1/corpus/captures/")));
    }

    private CorpusUploadResponse buildResponse(boolean duplicate, boolean force) {
        CorpusRecordResponse record = new CorpusRecordResponse();
        record.setId(1L);
        record.setCaptureId("capture_1");
        record.setCommentIndex(1);
        record.setPlatform("platform");
        record.setParseStatus("SUCCESS");
        record.setRawContent("raw content");
        record.setContextTarget("context target");
        record.setTags(List.of("tag"));
        record.setMarkdownPath("obsidian-output/test.md");

        CorpusUploadResponse response = new CorpusUploadResponse();
        response.setCaptureId("capture_1");
        response.setImageHash("hash_1");
        response.setProvider("gemini");
        response.setModel("gemini-1.5-flash");
        response.setDuplicate(duplicate);
        response.setForce(force);
        response.setRecords(List.of(record));
        return response;
    }
}
