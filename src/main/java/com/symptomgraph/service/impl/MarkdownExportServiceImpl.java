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
    public String export(CorpusRecord record) {
        String filename = buildFilename(record);
        Path outputPath = Path.of(properties.getOutputDir(), filename);

        try {
            Files.createDirectories(outputPath.getParent());
            Files.writeString(outputPath, buildMarkdown(record), StandardCharsets.UTF_8);
            return outputPath.toString().replace('\\', '/');
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to export markdown file", ex);
        }
    }

    private String buildMarkdown(CorpusRecord record) {
        List<String> tags = parseTags(record.getTags());
        StringBuilder markdown = new StringBuilder();
        markdown.append("---\n");
        markdown.append("id: ").append(record.getId()).append("\n");
        markdown.append("capture_id: \"").append(escapeYaml(record.getCaptureId())).append("\"\n");
        markdown.append("comment_index: ").append(record.getCommentIndex()).append("\n");
        markdown.append("platform: \"").append(escapeYaml(record.getPlatform())).append("\"\n");
        markdown.append("original_publish_time: ").append(record.getOriginalPublishTime() == null ? "" : DATE_TIME_FORMATTER.format(record.getOriginalPublishTime())).append("\n");
        markdown.append("collected_time: \"").append(record.getCollectedTime() == null ? "" : DATE_TIME_FORMATTER.format(record.getCollectedTime())).append("\"\n");
        markdown.append("tags:\n");
        for (String tag : tags) {
            markdown.append("  - \"").append(escapeYaml(tag)).append("\"\n");
        }
        markdown.append("obsidian_tags:\n");
        for (String tag : tags) {
            markdown.append("  - \"#").append(escapeYaml(tag)).append("\"\n");
        }
        markdown.append("image_hash: \"").append(escapeYaml(record.getImageHash())).append("\"\n");
        markdown.append("oss_object_key: \"").append(escapeYaml(record.getOssObjectKey())).append("\"\n");
        markdown.append("---\n\n");
        markdown.append("# ").append(valueOrUnknown(record.getPlatform())).append("语料 ").append(record.getId()).append("\n\n");
        markdown.append("## 原始评论\n\n");
        markdown.append("> ").append(valueOrEmpty(record.getRawContent()).replace("\n", "\n> ")).append("\n\n");
        markdown.append("## 上下文原文\n\n");
        markdown.append("> ").append(valueOrEmpty(record.getContextTarget()).replace("\n", "\n> ")).append("\n\n");
        markdown.append("## 证据链\n\n");
        markdown.append("- image_hash: `").append(valueOrEmpty(record.getImageHash())).append("`\n");
        markdown.append("- oss_object_key: `").append(valueOrEmpty(record.getOssObjectKey())).append("`\n\n");
        markdown.append("## 研究备注\n");
        return markdown.toString();
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

    private String buildFilename(CorpusRecord record) {
        String captureId = StringUtils.hasText(record.getCaptureId()) ? record.getCaptureId() : "unknown-capture";
        String commentIndex = record.getCommentIndex() == null ? "0" : record.getCommentIndex().toString();
        String platform = StringUtils.hasText(record.getPlatform()) ? record.getPlatform() : "unknown";
        String filename = sanitizeFilename(captureId + "-" + commentIndex + "-" + platform + ".md");
        if (filename.length() <= MAX_FILENAME_LENGTH) {
            return filename;
        }

        String extension = ".md";
        return filename.substring(0, MAX_FILENAME_LENGTH - extension.length()) + extension;
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
