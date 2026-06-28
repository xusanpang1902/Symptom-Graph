package com.symptomgraph.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.symptomgraph.dto.CorpusReviewRequest;
import com.symptomgraph.entity.CorpusRecord;
import com.symptomgraph.mapper.CorpusRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CorpusRecordReviewServiceTest {

    private CorpusRecordMapper mapper;
    private CorpusRecordServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(CorpusRecordMapper.class);
        service = new CorpusRecordServiceImpl(new ObjectMapper());
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
    }

    @Test
    void reviewCorrectedStoresReviewedFieldsAndSanitizesTags() {
        CorpusRecord record = buildRecord();
        when(mapper.selectById(1L)).thenReturn(record);
        when(mapper.updateById(any(CorpusRecord.class))).thenReturn(1);

        CorpusReviewRequest request = new CorpusReviewRequest();
        request.setReviewStatus("CORRECTED");
        request.setReviewedRawContent(" 人工评论 ");
        request.setReviewedContextTarget(" 人工上下文 ");
        request.setReviewedTags(List.of("#医疗焦虑", "＃恐艾", "医疗焦虑", " "));
        request.setReviewNote(" 已核对 ");

        CorpusRecord result = service.review(1L, request);

        assertThat(result.getReviewStatus()).isEqualTo("CORRECTED");
        assertThat(result.getReviewedRawContent()).isEqualTo("人工评论");
        assertThat(result.getReviewedContextTarget()).isEqualTo("人工上下文");
        assertThat(result.getReviewedTags()).isEqualTo("[\"医疗焦虑\",\"恐艾\"]");
        assertThat(result.getReviewNote()).isEqualTo("已核对");
        assertThat(result.getRawContent()).isEqualTo("模型评论");
        assertThat(result.getImageHash()).isEqualTo("hash_1");
    }

    @Test
    void reviewCorrectedRejectsEmptyReviewedFields() {
        when(mapper.selectById(1L)).thenReturn(buildRecord());
        CorpusReviewRequest request = new CorpusReviewRequest();
        request.setReviewStatus("CORRECTED");

        assertThatThrownBy(() -> service.review(1L, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("CORRECTED review requires at least one reviewed field");
    }

    @Test
    void reviewReviewedClearsCorrectedFieldsButKeepsNote() {
        CorpusRecord record = buildRecord();
        record.setReviewStatus("CORRECTED");
        record.setReviewedRawContent("旧人工评论");
        record.setReviewedTags("[\"旧标签\"]");
        when(mapper.selectById(1L)).thenReturn(record);
        when(mapper.updateById(any(CorpusRecord.class))).thenReturn(1);

        CorpusReviewRequest request = new CorpusReviewRequest();
        request.setReviewStatus("REVIEWED");
        request.setReviewNote("确认模型结果");

        CorpusRecord result = service.review(1L, request);

        assertThat(result.getReviewStatus()).isEqualTo("REVIEWED");
        assertThat(result.getReviewedRawContent()).isNull();
        assertThat(result.getReviewedTags()).isNull();
        assertThat(result.getReviewNote()).isEqualTo("确认模型结果");
        assertThat(result.getReviewedAt()).isNotNull();
    }

    @Test
    void reviewUnreviewedClearsReviewState() {
        CorpusRecord record = buildRecord();
        record.setReviewStatus("CORRECTED");
        record.setReviewedRawContent("旧人工评论");
        record.setReviewNote("旧备注");
        when(mapper.selectById(1L)).thenReturn(record);
        when(mapper.updateById(any(CorpusRecord.class))).thenReturn(1);

        CorpusReviewRequest request = new CorpusReviewRequest();
        request.setReviewStatus("UNREVIEWED");

        CorpusRecord result = service.review(1L, request);

        assertThat(result.getReviewStatus()).isEqualTo("UNREVIEWED");
        assertThat(result.getReviewedRawContent()).isNull();
        assertThat(result.getReviewNote()).isNull();
        assertThat(result.getReviewedAt()).isNull();
    }

    private CorpusRecord buildRecord() {
        CorpusRecord record = new CorpusRecord();
        record.setId(1L);
        record.setRawContent("模型评论");
        record.setContextTarget("模型上下文");
        record.setTags("[\"模型标签\"]");
        record.setImageHash("hash_1");
        record.setOssObjectKey("corpus/test.png");
        return record;
    }
}
