package com.symptomgraph.integration.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.symptomgraph.config.FeishuProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.Optional;

@Component
public class FeishuEventParser {

    private static final String MESSAGE_RECEIVE_EVENT = "im.message.receive_v1";

    private final FeishuProperties properties;
    private final ObjectMapper objectMapper;

    public FeishuEventParser(FeishuProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public JsonNode parseBody(String rawBody) {
        try {
            return objectMapper.readTree(rawBody);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Feishu callback body is not valid JSON", ex);
        }
    }

    public boolean isUrlVerification(JsonNode root) {
        return "url_verification".equals(root.path("type").asText());
    }

    public String challenge(JsonNode root) {
        validateToken(root.path("token").asText(null));
        String challenge = root.path("challenge").asText(null);
        if (!StringUtils.hasText(challenge)) {
            throw new IllegalArgumentException("Feishu url_verification challenge is blank");
        }
        return challenge;
    }

    public Optional<FeishuImageMessageEvent> parseImageMessageEvent(JsonNode root) {
        if (root.hasNonNull("encrypt")) {
            throw new IllegalArgumentException("Encrypted Feishu callbacks are not supported yet");
        }
        JsonNode header = root.path("header");
        String eventType = header.path("event_type").asText();
        if (!MESSAGE_RECEIVE_EVENT.equals(eventType)) {
            return Optional.empty();
        }
        validateToken(header.path("token").asText(null));

        JsonNode message = root.path("event").path("message");
        if (!"image".equals(message.path("message_type").asText())) {
            return Optional.empty();
        }

        String messageId = message.path("message_id").asText(null);
        String chatId = message.path("chat_id").asText(null);
        String imageKey = parseImageKey(message.path("content").asText(null));
        if (!StringUtils.hasText(messageId) || !StringUtils.hasText(imageKey)) {
            throw new IllegalArgumentException("Feishu image message missing message_id or image_key");
        }

        return Optional.of(new FeishuImageMessageEvent(
                header.path("event_id").asText(messageId + ":" + imageKey),
                messageId,
                chatId,
                parseSenderId(root.path("event").path("sender")),
                imageKey
        ));
    }

    private String parseImageKey(String contentJson) {
        if (!StringUtils.hasText(contentJson)) {
            return null;
        }
        try {
            JsonNode content = objectMapper.readTree(contentJson);
            return content.path("image_key").asText(null);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Feishu image message content is not valid JSON", ex);
        }
    }

    private String parseSenderId(JsonNode sender) {
        JsonNode senderId = sender.path("sender_id");
        String openId = senderId.path("open_id").asText(null);
        if (StringUtils.hasText(openId)) {
            return openId;
        }
        String userId = senderId.path("user_id").asText(null);
        return StringUtils.hasText(userId) ? userId : null;
    }

    private void validateToken(String callbackToken) {
        if (!StringUtils.hasText(properties.getVerificationToken())) {
            return;
        }
        if (!properties.getVerificationToken().equals(callbackToken)) {
            throw new IllegalArgumentException("Feishu callback verification token mismatch");
        }
    }
}
