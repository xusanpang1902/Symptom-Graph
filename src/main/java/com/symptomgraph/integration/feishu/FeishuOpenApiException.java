package com.symptomgraph.integration.feishu;

public class FeishuOpenApiException extends RuntimeException {

    public FeishuOpenApiException(String message) {
        super(message);
    }

    public FeishuOpenApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
