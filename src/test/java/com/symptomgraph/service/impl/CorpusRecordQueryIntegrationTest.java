package com.symptomgraph.service.impl;

import com.symptomgraph.dto.CorpusQueryPage;
import com.symptomgraph.dto.CorpusQueryRequest;
import com.symptomgraph.entity.CorpusRecord;
import com.symptomgraph.mapper.CorpusRecordMapper;
import com.symptomgraph.service.CorpusRecordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(properties = {
        "app.oss.endpoint=https://oss-cn-hangzhou.aliyuncs.com",
        "app.oss.bucket=test-bucket",
        "app.oss.access-key-id=test-access-key-id",
        "app.oss.access-key-secret=test-access-key-secret",
        "app.rabbitmq.listener-auto-startup=false",
        "app.bloom.enabled=false"
})
class CorpusRecordQueryIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("symptom_graph_test")
            .withUsername("test")
            .withPassword("test")
            .withInitScript("db/schema.sql");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private CorpusRecordService corpusRecordService;

    @Autowired
    private CorpusRecordMapper corpusRecordMapper;

    @BeforeEach
    void setUp() {
        corpusRecordMapper.delete(null);
        insertRecord(1L, "capture_1", 1, "正文提到检测", "无关上下文", "小红书",
                "[\"医疗焦虑\"]", LocalDateTime.of(2026, 6, 1, 10, 0));
        insertRecord(2L, "capture_2", 1, "无关正文", "上下文提到检测", "知乎",
                "[\"恐艾\"]", LocalDateTime.of(2026, 6, 1, 11, 0));
        insertRecord(3L, "capture_3", 1, "医疗讨论", "无关上下文", "小红书",
                "[\"医疗\"]", LocalDateTime.of(2026, 6, 1, 12, 0));
    }

    @Test
    void searchesExactJsonTagsAndSelectedTextFields() {
        CorpusQueryRequest tagRequest = new CorpusQueryRequest();
        tagRequest.setTag("医疗焦虑");

        CorpusQueryPage tagResult = corpusRecordService.search(tagRequest);

        assertThat(tagResult.total()).isEqualTo(1);
        assertThat(tagResult.records()).extracting(CorpusRecord::getId).containsExactly(1L);

        CorpusQueryRequest rawContentRequest = new CorpusQueryRequest();
        rawContentRequest.setKeyword("检测");
        rawContentRequest.setSearchFields(List.of("rawContent"));

        assertThat(corpusRecordService.search(rawContentRequest).records())
                .extracting(CorpusRecord::getId)
                .containsExactly(1L);

        CorpusQueryRequest contextTargetRequest = new CorpusQueryRequest();
        contextTargetRequest.setKeyword("检测");
        contextTargetRequest.setSearchFields(List.of("contextTarget"));

        assertThat(corpusRecordService.search(contextTargetRequest).records())
                .extracting(CorpusRecord::getId)
                .containsExactly(2L);
    }

    @Test
    void appliesHalfOpenTimeRangeAndStablePagination() {
        CorpusQueryRequest firstPageRequest = new CorpusQueryRequest();
        firstPageRequest.setCollectedFrom(LocalDateTime.of(2026, 6, 1, 10, 0));
        firstPageRequest.setCollectedTo(LocalDateTime.of(2026, 6, 1, 12, 0));
        firstPageRequest.setPageSize(1);

        CorpusQueryPage firstPage = corpusRecordService.search(firstPageRequest);

        assertThat(firstPage.total()).isEqualTo(2);
        assertThat(firstPage.totalPages()).isEqualTo(2);
        assertThat(firstPage.records()).extracting(CorpusRecord::getId).containsExactly(2L);

        firstPageRequest.setPage(2);
        CorpusQueryPage secondPage = corpusRecordService.search(firstPageRequest);
        assertThat(secondPage.records()).extracting(CorpusRecord::getId).containsExactly(1L);
    }

    @Test
    void normalizesPaginationAndRejectsInvalidCriteria() {
        CorpusQueryRequest normalizedRequest = new CorpusQueryRequest();
        normalizedRequest.setPage(0);
        normalizedRequest.setPageSize(500);

        CorpusQueryPage normalizedResult = corpusRecordService.search(normalizedRequest);
        assertThat(normalizedResult.page()).isEqualTo(1);
        assertThat(normalizedResult.pageSize()).isEqualTo(100);

        CorpusQueryRequest invalidSearchField = new CorpusQueryRequest();
        invalidSearchField.setKeyword("检测");
        invalidSearchField.setSearchFields(List.of("title"));
        assertThatThrownBy(() -> corpusRecordService.search(invalidSearchField))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        CorpusQueryRequest invalidTimeRange = new CorpusQueryRequest();
        invalidTimeRange.setCollectedFrom(LocalDateTime.of(2026, 6, 2, 0, 0));
        invalidTimeRange.setCollectedTo(LocalDateTime.of(2026, 6, 1, 0, 0));
        assertThatThrownBy(() -> corpusRecordService.search(invalidTimeRange))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private void insertRecord(long id,
                              String captureId,
                              int commentIndex,
                              String rawContent,
                              String contextTarget,
                              String platform,
                              String tags,
                              LocalDateTime collectedTime) {
        CorpusRecord record = new CorpusRecord();
        record.setId(id);
        record.setCaptureId(captureId);
        record.setCommentIndex(commentIndex);
        record.setRawContent(rawContent);
        record.setContextTarget(contextTarget);
        record.setPlatform(platform);
        record.setCollectedTime(collectedTime);
        record.setOssBucket("test-bucket");
        record.setOssObjectKey("corpus/test-" + id + ".png");
        record.setImageHash("hash-" + id);
        record.setTags(tags);
        record.setParseStatus("SUCCESS");
        record.setCreatedAt(collectedTime);
        record.setUpdatedAt(collectedTime);
        corpusRecordMapper.insert(record);
    }
}
