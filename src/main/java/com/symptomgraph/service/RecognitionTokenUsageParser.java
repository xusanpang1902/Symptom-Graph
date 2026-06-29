package com.symptomgraph.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.symptomgraph.dto.RecognitionTokenUsage;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;

@Component
public class RecognitionTokenUsageParser {

    private final ObjectMapper objectMapper;

    public RecognitionTokenUsageParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public RecognitionTokenUsage parse(String provider, String modelRawResponse) {
        if (!StringUtils.hasText(provider) || !StringUtils.hasText(modelRawResponse)) {
            return empty();
        }
        try {
            JsonNode root = objectMapper.readTree(modelRawResponse);
            return switch (provider.trim().toLowerCase(Locale.ROOT)) {
                case "gemini" -> parseGemini(root);
                case "openrouter" -> parseOpenRouter(root);
                default -> empty();
            };
        } catch (JsonProcessingException ex) {
            return empty();
        }
    }

    private RecognitionTokenUsage parseGemini(JsonNode root) {
        JsonNode usage = root.path("usageMetadata");
        Long inputTokens = tokenValue(usage, "promptTokenCount");
        Long outputTokens = tokenValue(usage, "candidatesTokenCount");
        Long totalTokens = tokenValue(usage, "totalTokenCount");
        return usage(inputTokens, outputTokens, totalTokens);
    }

    private RecognitionTokenUsage parseOpenRouter(JsonNode root) {
        JsonNode usage = root.path("usage");
        Long inputTokens = tokenValue(usage, "prompt_tokens");
        Long outputTokens = tokenValue(usage, "completion_tokens");
        Long totalTokens = tokenValue(usage, "total_tokens");
        return usage(inputTokens, outputTokens, totalTokens);
    }

    private RecognitionTokenUsage usage(Long inputTokens, Long outputTokens, Long totalTokens) {
        if (totalTokens == null && inputTokens != null && outputTokens != null) {
            totalTokens = inputTokens + outputTokens;
        }
        return new RecognitionTokenUsage(inputTokens, outputTokens, totalTokens);
    }

    private Long tokenValue(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (!value.isNumber()) {
            return null;
        }
        return value.asLong();
    }

    private RecognitionTokenUsage empty() {
        return new RecognitionTokenUsage(null, null, null);
    }
}
