package com.symptomgraph.exception;

public class GeminiRecognitionException extends VisionRecognitionException {

    public GeminiRecognitionException(String parseStatus, String message) {
        this(parseStatus, message, null, null);
    }

    public GeminiRecognitionException(String parseStatus, String message, Throwable cause) {
        this(parseStatus, message, null, cause);
    }

    public GeminiRecognitionException(String parseStatus, String message, String modelRawResponse, Throwable cause) {
        super(parseStatus, message, modelRawResponse, cause);
    }
}
