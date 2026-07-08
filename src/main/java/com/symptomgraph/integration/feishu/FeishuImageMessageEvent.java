package com.symptomgraph.integration.feishu;

public record FeishuImageMessageEvent(
        String eventId,
        String messageId,
        String chatId,
        String senderId,
        String imageKey
) {
}
