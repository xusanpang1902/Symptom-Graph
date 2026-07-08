package com.symptomgraph.integration.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.symptomgraph.config.FeishuProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;

@Component
public class RestClientFeishuOpenApiClient implements FeishuOpenApiClient {

    private static final long TOKEN_EXPIRY_SKEW_SECONDS = 60;

    private final FeishuProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    private String tenantAccessToken;
    private Instant tenantAccessTokenExpiresAt = Instant.EPOCH;

    public RestClientFeishuOpenApiClient(FeishuProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .build();
    }

    @Override
    public FeishuImageResource downloadImage(String messageId, String imageKey) {
        String token = tenantAccessToken();
        ResponseEntity<byte[]> response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/im/v1/messages/{messageId}/resources/{imageKey}")
                        .queryParam("type", "image")
                .build(messageId, imageKey))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .toEntity(byte[].class);
        byte[] bytes = response.getBody();
        if (bytes == null || bytes.length == 0) {
            throw new FeishuOpenApiException("Feishu image resource is empty");
        }
        MediaType contentType = response.getHeaders().getContentType();
        String resolvedContentType = contentType == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : contentType.toString();
        return new FeishuImageResource(bytes, resolvedContentType, imageKey + extension(resolvedContentType));
    }

    @Override
    public void sendTextMessage(String chatId, String text) {
        if (!StringUtils.hasText(chatId)) {
            return;
        }
        String token = tenantAccessToken();
        Map<String, Object> requestBody = Map.of(
                "receive_id", chatId,
                "msg_type", "text",
                "content", toJson(Map.of("text", text))
        );
        restClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/im/v1/messages")
                        .queryParam("receive_id_type", "chat_id")
                        .build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(String.class);
    }

    private synchronized String tenantAccessToken() {
        if (StringUtils.hasText(tenantAccessToken) && Instant.now().isBefore(tenantAccessTokenExpiresAt)) {
            return tenantAccessToken;
        }
        if (!StringUtils.hasText(properties.getAppId()) || !StringUtils.hasText(properties.getAppSecret())) {
            throw new FeishuOpenApiException("Feishu app-id and app-secret must be configured");
        }
        String responseBody = restClient.post()
                .uri("/auth/v3/tenant_access_token/internal")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "app_id", properties.getAppId(),
                        "app_secret", properties.getAppSecret()
                ))
                .retrieve()
                .body(String.class);
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (root.path("code").asInt(-1) != 0) {
                throw new FeishuOpenApiException("Failed to get Feishu tenant access token: " + root.path("msg").asText());
            }
            String token = root.path("tenant_access_token").asText();
            if (!StringUtils.hasText(token)) {
                throw new FeishuOpenApiException("Feishu tenant access token is blank");
            }
            long expiresIn = root.path("expire").asLong(7200);
            tenantAccessToken = token;
            tenantAccessTokenExpiresAt = Instant.now().plusSeconds(Math.max(1, expiresIn - TOKEN_EXPIRY_SKEW_SECONDS));
            return tenantAccessToken;
        } catch (FeishuOpenApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new FeishuOpenApiException("Failed to parse Feishu tenant access token response", ex);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new FeishuOpenApiException("Failed to serialize Feishu message content", ex);
        }
    }

    private String extension(String contentType) {
        if (MediaType.IMAGE_JPEG_VALUE.equalsIgnoreCase(contentType)) {
            return ".jpg";
        }
        if (MediaType.IMAGE_PNG_VALUE.equalsIgnoreCase(contentType)) {
            return ".png";
        }
        if ("image/webp".equalsIgnoreCase(contentType)) {
            return ".webp";
        }
        return "";
    }
}
