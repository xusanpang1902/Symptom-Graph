package com.symptomgraph.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.symptomgraph.config.MarkdownProperties;
import com.symptomgraph.entity.CorpusRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownExportServiceImplTest {

    @TempDir
    private Path tempDir;

    @Test
    void exportWritesObsidianMarkdownWithoutSignedUrl() throws IOException {
        MarkdownExportServiceImpl service = new MarkdownExportServiceImpl(properties(), new ObjectMapper());
        CorpusRecord record = buildRecord();
        record.setTags("[\"医疗焦虑\",\"恐艾\"]");

        String markdownPath = service.export(record);

        assertThat(markdownPath).endsWith("20260529_abcd-1-小红书.md");
        assertThat(markdownPath).contains("obsidian-output");

        String markdown = Files.readString(Path.of(markdownPath));
        assertThat(markdown).contains("tags:\n  - \"医疗焦虑\"\n  - \"恐艾\"");
        assertThat(markdown).contains("obsidian_tags:\n  - \"#医疗焦虑\"\n  - \"#恐艾\"");
        assertThat(markdown).contains("review_status: \"UNREVIEWED\"");
        assertThat(markdown).contains("content_version: \"model\"");
        assertThat(markdown).contains("oss_object_key: \"corpus/2026/05/test.png\"");
        assertThat(markdown).contains("- oss_object_key: `corpus/2026/05/test.png`");
        assertThat(markdown).doesNotContain("http://");
        assertThat(markdown).doesNotContain("https://");
    }

    @Test
    void exportUsesStablePathAndOverwritesExistingFile() throws IOException {
        MarkdownExportServiceImpl service = new MarkdownExportServiceImpl(properties(), new ObjectMapper());
        CorpusRecord record = buildRecord();
        record.setRawContent("第一次内容");

        String firstPath = service.export(record);
        record.setRawContent("第二次内容");
        String secondPath = service.export(record);

        assertThat(secondPath).isEqualTo(firstPath);
        String markdown = Files.readString(Path.of(secondPath));
        assertThat(markdown).contains("第二次内容");
        assertThat(markdown).doesNotContain("第一次内容");
    }

    @Test
    void exportSanitizesInvalidFilenameCharacters() {
        MarkdownExportServiceImpl service = new MarkdownExportServiceImpl(properties(), new ObjectMapper());
        CorpusRecord record = buildRecord();
        record.setCaptureId("capture:bad/name");
        record.setPlatform("小红书?非法");

        String markdownPath = service.export(record);

        assertThat(Path.of(markdownPath).getFileName().toString()).isEqualTo("capture_bad_name-1-小红书_非法.md");
    }

    @Test
    void exportCanUseReviewedContentWhenConfigured() throws IOException {
        MarkdownProperties properties = properties();
        properties.setContentVersion("reviewed");
        MarkdownExportServiceImpl service = new MarkdownExportServiceImpl(properties, new ObjectMapper());
        CorpusRecord record = buildRecord();
        record.setTags("[\"模型标签\"]");
        record.setReviewStatus("CORRECTED");
        record.setReviewedRawContent("人工修正评论");
        record.setReviewedContextTarget("人工修正上下文");
        record.setReviewedTags("[\"人工标签\"]");

        String markdownPath = service.export(record);

        String markdown = Files.readString(Path.of(markdownPath));
        assertThat(markdown).contains("review_status: \"CORRECTED\"");
        assertThat(markdown).contains("content_version: \"reviewed\"");
        assertThat(markdown).contains("> 人工修正评论");
        assertThat(markdown).contains("> 人工修正上下文");
        assertThat(markdown).contains("tags:\n  - \"人工标签\"");
        assertThat(markdown).doesNotContain("> 截图中提取出的评论原文");
    }

    private MarkdownProperties properties() {
        MarkdownProperties properties = new MarkdownProperties();
        properties.setOutputDir(tempDir.resolve("obsidian-output").toString());
        return properties;
    }

    private CorpusRecord buildRecord() {
        CorpusRecord record = new CorpusRecord();
        record.setId(123L);
        record.setCaptureId("20260529_abcd");
        record.setCommentIndex(1);
        record.setPlatform("小红书");
        record.setCollectedTime(LocalDateTime.of(2026, 5, 29, 14, 30));
        record.setRawContent("截图中提取出的评论原文");
        record.setContextTarget("截图中可见的上下文原文");
        record.setImageHash("abc123");
        record.setOssObjectKey("corpus/2026/05/test.png");
        record.setTags("[]");
        return record;
    }
}
