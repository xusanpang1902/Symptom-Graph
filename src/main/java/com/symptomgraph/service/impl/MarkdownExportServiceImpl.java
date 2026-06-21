package com.symptomgraph.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.symptomgraph.config.MarkdownProperties;
import com.symptomgraph.entity.CorpusRecord;
import com.symptomgraph.service.MarkdownExportService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class MarkdownExportServiceImpl implements MarkdownExportService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_FILENAME_LENGTH = 180;

    private final MarkdownProperties properties;
    private final ObjectMapper objectMapper;

    public MarkdownExportServiceImpl(MarkdownProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String export(CorpusRecord corpusRecord) {
        String markdownFilename = buildMarkdownFilename(corpusRecord);
        Path markdownOutputPath = Path.of(properties.getOutputDir(), markdownFilename);

        try {
            Files.createDirectories(markdownOutputPath.getParent());
            Files.writeString(markdownOutputPath, buildMarkdownContent(corpusRecord), StandardCharsets.UTF_8);
            return markdownOutputPath.toString().replace('\\', '/');
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to export markdown file", ex);
        }
    }

    private String buildMarkdownContent(CorpusRecord corpusRecord) {
        List<String> databaseTags = parseTags(corpusRecord.getTags());
        StringBuilder markdownContent = new StringBuilder();

        // Markdown 是长期研究资料，只写稳定证据链字段，不写会过期的 signed URL。
        markdownContent.append("---\n");
        markdownContent.append("id: ").append(corpusRecord.getId()).append("\n");
        markdownContent.append("capture_id: \"").append(escapeYaml(corpusRecord.getCaptureId())).append("\"\n");
        markdownContent.append("comment_index: ").append(corpusRecord.getCommentIndex()).append("\n");
        markdownContent.append("platform: \"").append(escapeYaml(corpusRecord.getPlatform())).append("\"\n");
        markdownContent.append("original_publish_time: ").append(corpusRecord.getOriginalPublishTime() == null ? "" : DATE_TIME_FORMATTER.format(corpusRecord.getOriginalPublishTime())).append("\n");
        markdownContent.append("collected_time: \"").append(corpusRecord.getCollectedTime() == null ? "" : DATE_TIME_FORMATTER.format(corpusRecord.getCollectedTime())).append("\"\n");
        markdownContent.append("tags:\n");
        for (String tag : databaseTags) {
            markdownContent.append("  - \"").append(escapeYaml(tag)).append("\"\n");
        }
        markdownContent.append("obsidian_tags:\n");
        for (String tag : databaseTags) {
            markdownContent.append("  - \"#").append(escapeYaml(tag)).append("\"\n");
        }
        markdownContent.append("image_hash: \"").append(escapeYaml(corpusRecord.getImageHash())).append("\"\n");
        markdownContent.append("oss_object_key: \"").append(escapeYaml(corpusRecord.getOssObjectKey())).append("\"\n");
        markdownContent.append("---\n\n");
        markdownContent.append("# ").append(valueOrUnknown(corpusRecord.getPlatform())).append("语料 ").append(corpusRecord.getId()).append("\n\n");
        markdownContent.append("## 原始评论\n\n");
        markdownContent.append("> ").append(valueOrEmpty(corpusRecord.getRawContent()).replace("\n", "\n> ")).append("\n\n");
        markdownContent.append("## 上下文原文\n\n");
        markdownContent.append("> ").append(valueOrEmpty(corpusRecord.getContextTarget()).replace("\n", "\n> ")).append("\n\n");
        markdownContent.append("## 证据链\n\n");
        markdownContent.append("- image_hash: `").append(valueOrEmpty(corpusRecord.getImageHash())).append("`\n");
        markdownContent.append("- oss_object_key: `").append(valueOrEmpty(corpusRecord.getOssObjectKey())).append("`\n\n");
        markdownContent.append("## 研究备注\n");
        return markdownContent.toString();
    }

    private List<String> parseTags(String tagsJson) {
        if (!StringUtils.hasText(tagsJson)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(tagsJson, new TypeReference<>() {
            });
        } catch (IOException ex) {
            return List.of();
        }
    }

    private String buildMarkdownFilename(CorpusRecord corpusRecord) {
        String captureBatchId = StringUtils.hasText(corpusRecord.getCaptureId()) ? corpusRecord.getCaptureId() : "unknown-capture";
        String commentIndex = corpusRecord.getCommentIndex() == null ? "0" : corpusRecord.getCommentIndex().toString();
        String platform = StringUtils.hasText(corpusRecord.getPlatform()) ? corpusRecord.getPlatform() : "unknown";
        String markdownFilename = sanitizeFilename(captureBatchId + "-" + commentIndex + "-" + platform + ".md");
        if (markdownFilename.length() <= MAX_FILENAME_LENGTH) {
            return markdownFilename;
        }

        String extension = ".md";
        return markdownFilename.substring(0, MAX_FILENAME_LENGTH - extension.length()) + extension;
    }

    private String sanitizeFilename(String filename) {
        String sanitized = filename.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return StringUtils.hasText(sanitized) ? sanitized : "unknown.md";
    }

    private String escapeYaml(String value) {
        return valueOrEmpty(value).replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String valueOrUnknown(String value) {
        return StringUtils.hasText(value) ? value : "未知平台";
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
