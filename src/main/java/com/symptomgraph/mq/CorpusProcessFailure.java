package com.symptomgraph.mq;

public record CorpusProcessFailure(
        String errorType,
        String parseStatus,
        String errorMessage,
        String modelRawResponse,
        boolean retryable
) {
}
