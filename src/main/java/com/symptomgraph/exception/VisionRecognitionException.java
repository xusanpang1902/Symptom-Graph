package com.symptomgraph.exception;

public class VisionRecognitionException extends RuntimeException {

    private final String parseStatus;
    private final String modelRawResponse;

    public VisionRecognitionException(String parseStatus, String message) {
        this(parseStatus, message, null, null);
    }

    public VisionRecognitionException(String parseStatus, String message, Throwable cause) {
        this(parseStatus, message, null, cause);
    }

    public VisionRecognitionException(String parseStatus, String message, String modelRawResponse, Throwable cause) {
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
