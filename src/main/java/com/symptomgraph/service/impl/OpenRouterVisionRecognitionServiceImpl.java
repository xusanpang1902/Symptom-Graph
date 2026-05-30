package com.symptomgraph.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.symptomgraph.config.OpenRouterProperties;
import com.symptomgraph.dto.VisionRecognitionResult;
import com.symptomgraph.exception.VisionRecognitionException;
import com.symptomgraph.service.VisionRecognitionProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Base64;
import java.util.List;
import java.util.Map;

@Service
public class OpenRouterVisionRecognitionServiceImpl implements VisionRecognitionProvider {

    public static final String STATUS_MODEL_FAILED = "MODEL_FAILED";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final OpenRouterProperties properties;
    private final VisionRecognitionJsonParser jsonParser;

    public OpenRouterVisionRecognitionServiceImpl(RestClient.Builder restClientBuilder,
                                                  ObjectMapper objectMapper,
                                                  OpenRouterProperties properties,
                                                  VisionRecognitionJsonParser jsonParser) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.jsonParser = jsonParser;
    }

    @Override
    public String providerName() {
        return "openrouter";
    }

    @Override
    public VisionRecognitionResult recognize(byte[] imageBytes, String mimeType) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new VisionRecognitionException(STATUS_MODEL_FAILED, "Image bytes must not be empty");
        }
        if (!StringUtils.hasText(mimeType)) {
            throw new VisionRecognitionException(STATUS_MODEL_FAILED, "Image mimeType must not be blank");
        }
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new VisionRecognitionException(STATUS_MODEL_FAILED, "OpenRouter API key is not configured");
        }

        String responseBody = callOpenRouter(imageBytes, mimeType);
        String modelText = extractMessageContent(responseBody);
        VisionRecognitionResult result;
        try {
            result = jsonParser.parse(modelText);
        } catch (VisionRecognitionException ex) {
            throw new VisionRecognitionException(ex.getParseStatus(), ex.getMessage(), responseBody, ex);
        }
        result.setModelRawResponse(responseBody);
        return result;
    }

    private String callOpenRouter(byte[] imageBytes, String mimeType) {
        Map<String, Object> requestBody = buildRequestBody(imageBytes, mimeType);
        try {
            String responseBody = restClient.post()
                    .uri(buildChatCompletionsUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(this::applyHeaders)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            if (!StringUtils.hasText(responseBody)) {
                throw new VisionRecognitionException(STATUS_MODEL_FAILED, "OpenRouter returned an empty response");
            }
            return responseBody;
        } catch (RestClientResponseException ex) {
            throw new VisionRecognitionException(
                    STATUS_MODEL_FAILED,
                    "OpenRouter API request failed with status " + ex.getStatusCode(),
                    ex.getResponseBodyAsString(),
                    ex
            );
        } catch (RestClientException ex) {
            throw new VisionRecognitionException(STATUS_MODEL_FAILED, "OpenRouter API request failed", ex);
        }
    }

    private Map<String, Object> buildRequestBody(byte[] imageBytes, String mimeType) {
        String dataUrl = "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(imageBytes);
        return Map.of(
                "model", properties.getModel(),
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "text", "text", VisionRecognitionPrompt.PROMPT),
                                Map.of("type", "image_url", "image_url", Map.of("url", dataUrl))
                        )
                )),
                "temperature", 0
        );
    }

    private void applyHeaders(HttpHeaders headers) {
        headers.setBearerAuth(normalizeApiKey(properties.getApiKey()));
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (StringUtils.hasText(properties.getReferer())) {
            headers.set("HTTP-Referer", properties.getReferer());
        }
        if (StringUtils.hasText(properties.getTitle())) {
            headers.set("X-Title", properties.getTitle());
        }
    }

    String buildChatCompletionsUrl() {
        String endpoint = properties.getEndpoint();
        if (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }
        if (endpoint.endsWith("/chat/completions")) {
            return endpoint;
        }
        return endpoint + "/chat/completions";
    }

    String normalizeApiKey(String apiKey) {
        String normalized = apiKey == null ? "" : apiKey.trim();
        if (normalized.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            return normalized.substring("Bearer ".length()).trim();
        }
        return normalized;
    }

    String extractMessageContent(String responseBody) {
        try {
            JsonNode content = objectMapper.readTree(responseBody)
                    .path("choices")
                    .path(0)
                    .path("message")
                    .path("content");
            if (content.isTextual() && StringUtils.hasText(content.asText())) {
                return content.asText();
            }
            if (content.isArray()) {
                StringBuilder text = new StringBuilder();
                for (JsonNode part : content) {
                    if (part.hasNonNull("text")) {
                        text.append(part.get("text").asText());
                    }
                }
                if (StringUtils.hasText(text)) {
                    return text.toString();
                }
            }
            throw new VisionRecognitionException(STATUS_MODEL_FAILED, "OpenRouter response contains no message content", responseBody, null);
        } catch (JsonProcessingException ex) {
            throw new VisionRecognitionException(STATUS_MODEL_FAILED, "OpenRouter response is not valid JSON", responseBody, ex);
        }
    }
}
