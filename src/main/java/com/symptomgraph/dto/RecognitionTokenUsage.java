package com.symptomgraph.dto;

public record RecognitionTokenUsage(
        Long inputTokens,
        Long outputTokens,
        Long totalTokens
) {
}
