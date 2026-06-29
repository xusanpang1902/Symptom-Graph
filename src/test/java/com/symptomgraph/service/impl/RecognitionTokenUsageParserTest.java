package com.symptomgraph.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.symptomgraph.dto.RecognitionTokenUsage;
import com.symptomgraph.service.RecognitionTokenUsageParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecognitionTokenUsageParserTest {

    private final RecognitionTokenUsageParser parser = new RecognitionTokenUsageParser(new ObjectMapper());

    @Test
    void parseReadsGeminiUsageMetadata() {
        String responseBody = """
                {
                  "candidates": [],
                  "usageMetadata": {
                    "promptTokenCount": 120,
                    "candidatesTokenCount": 34,
                    "totalTokenCount": 154
                  }
                }
                """;

        RecognitionTokenUsage usage = parser.parse("gemini", responseBody);

        assertThat(usage.inputTokens()).isEqualTo(120);
        assertThat(usage.outputTokens()).isEqualTo(34);
        assertThat(usage.totalTokens()).isEqualTo(154);
    }

    @Test
    void parseReadsOpenRouterUsage() {
        String responseBody = """
                {
                  "choices": [],
                  "usage": {
                    "prompt_tokens": 200,
                    "completion_tokens": 45,
                    "total_tokens": 245
                  }
                }
                """;

        RecognitionTokenUsage usage = parser.parse("openrouter", responseBody);

        assertThat(usage.inputTokens()).isEqualTo(200);
        assertThat(usage.outputTokens()).isEqualTo(45);
        assertThat(usage.totalTokens()).isEqualTo(245);
    }

    @Test
    void parseComputesTotalWhenProviderOmitsIt() {
        String responseBody = """
                {
                  "usage": {
                    "prompt_tokens": 200,
                    "completion_tokens": 45
                  }
                }
                """;

        RecognitionTokenUsage usage = parser.parse("openrouter", responseBody);

        assertThat(usage.inputTokens()).isEqualTo(200);
        assertThat(usage.outputTokens()).isEqualTo(45);
        assertThat(usage.totalTokens()).isEqualTo(245);
    }

    @Test
    void parseReturnsEmptyUsageForUnsupportedOrInvalidResponse() {
        RecognitionTokenUsage unsupportedProvider = parser.parse("unknown", "{\"usage\":{\"total_tokens\":1}}");
        RecognitionTokenUsage invalidJson = parser.parse("gemini", "not-json");

        assertThat(unsupportedProvider.inputTokens()).isNull();
        assertThat(unsupportedProvider.outputTokens()).isNull();
        assertThat(unsupportedProvider.totalTokens()).isNull();
        assertThat(invalidJson.inputTokens()).isNull();
        assertThat(invalidJson.outputTokens()).isNull();
        assertThat(invalidJson.totalTokens()).isNull();
    }
}
