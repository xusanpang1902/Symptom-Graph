package com.symptomgraph.integration.feishu;

public interface FeishuOpenApiClient {

    FeishuImageResource downloadImage(String messageId, String imageKey);

    void sendTextMessage(String chatId, String text);
}
