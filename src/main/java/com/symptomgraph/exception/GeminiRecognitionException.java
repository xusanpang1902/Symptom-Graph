package com.symptomgraph.exception;

public class GeminiRecognitionException extends RuntimeException {

    private final String parseStatus;
    private final String modelRawResponse;

    public GeminiRecognitionException(String parseStatus, String message) {
        this(parseStatus, message, null, null);
    }

    public GeminiRecognitionException(String parseStatus, String message, Throwable cause) {
        this(parseStatus, message, null, cause);
    }

    public GeminiRecognitionException(String parseStatus, String message, String modelRawResponse, Throwable cause) {
        super(message, cause);
        this.parseStatus = parseStatus;
        this.modelRawResponse = modelRawResponse;
    }

    public String getParseStatus() {
        return parseStatus;
    }

    public String getModelRawResponse() {
        return modelRawResponse;
    }
}
