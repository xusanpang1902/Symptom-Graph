package com.symptomgraph.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.symptomgraph.config.GeminiProperties;
import com.symptomgraph.dto.GeminiRecognitionResult;
import com.symptomgraph.exception.GeminiRecognitionException;
import com.symptomgraph.service.GeminiVisionService;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Service
public class GeminiVisionServiceImpl implements GeminiVisionService {

    public static final String STATUS_MODEL_FAILED = "MODEL_FAILED";
    public static final String STATUS_PARSE_FAILED = "PARSE_FAILED";

    private static final String PROMPT = """
            你是一个截图文字提取器，只能提取截图中实际可见文字。

            不得推测、不得总结、不得改写、不得补全。
            context_target 必须是截图中可见的上下文原文。
            raw_content 必须是截图中可见的评论原文。
            如果无法识别，返回 null 或空数组。
            tags 不带 #。
            tags 必须是现象性标签，不得对发言者做心理诊断或人格判断。

            只返回 JSON，不要返回 Markdown，不要解释。
            返回结构必须严格符合：
            {
              "platform": "小红书",
              "context_target": "截图中可见的上下文原文",
              "original_publish_time": null,
              "items": [
                {
                  "comment_index": 1,
                  "raw_content": "第一条评论原文",
                  "tags": ["医疗焦虑", "恐艾"]
                }
              ]
            }
            """;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final GeminiProperties properties;

    public GeminiVisionServiceImpl(RestClient.Builder restClientBuilder,
                                   ObjectMapper objectMapper,
                                   GeminiProperties properties) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public GeminiRecognitionResult recognize(byte[] imageBytes, String mimeType) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new GeminiRecognitionException(STATUS_MODEL_FAILED, "Image bytes must not be empty");
        }
        if (!StringUtils.hasText(mimeType)) {
            throw new GeminiRecognitionException(STATUS_MODEL_FAILED, "Image mimeType must not be blank");
        }
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new GeminiRecognitionException(STATUS_MODEL_FAILED, "Gemini API key is not configured");
        }

        String responseBody = callGemini(imageBytes, mimeType);
        String modelText = extractCandidateText(responseBody);
        GeminiRecognitionResult result;
        try {
            result = parseRecognitionJson(modelText);
        } catch (GeminiRecognitionException ex) {
            throw new GeminiRecognitionException(ex.getParseStatus(), ex.getMessage(), responseBody, ex);
        }
        result.setModelRawResponse(responseBody);
        return result;
    }

    private String callGemini(byte[] imageBytes, String mimeType) {
        Map<String, Object> requestBody = buildRequestBody(imageBytes, mimeType);

        try {
            String responseBody = restClient.post()
                    .uri(buildGenerateContentUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            if (!StringUtils.hasText(responseBody)) {
                throw new GeminiRecognitionException(STATUS_MODEL_FAILED, "Gemini returned an empty response");
            }
            return responseBody;
        } catch (RestClientResponseException ex) {
            throw new GeminiRecognitionException(
                    STATUS_MODEL_FAILED,
                    "Gemini API request failed with status " + ex.getStatusCode(),
                    ex.getResponseBodyAsString(),
                    ex
            );
        } catch (RestClientException ex) {
            throw new GeminiRecognitionException(STATUS_MODEL_FAILED, "Gemini API request failed", ex);
        }
    }

    private Map<String, Object> buildRequestBody(byte[] imageBytes, String mimeType) {
        String base64Image = Base64.getEncoder().encodeToString(imageBytes);

        return Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(
                                Map.of("text", PROMPT),
                                Map.of("inline_data", Map.of(
                                        "mime_type", mimeType,
                                        "data", base64Image
                                ))
                        ))
                ),
                "generationConfig", Map.of(
                        "temperature", 0,
                        "response_mime_type", "application/json"
                )
        );
    }

    private String buildGenerateContentUrl() {
        String endpoint = properties.getEndpoint();
        if (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }
        String apiKey = UriUtils.encodeQueryParam(properties.getApiKey(), StandardCharsets.UTF_8);
        return endpoint + "/models/" + properties.getModel() + ":generateContent?key=" + apiKey;
    }

    String extractCandidateText(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode parts = root.path("candidates").path(0).path("content").path("parts");
            if (!parts.isArray() || parts.isEmpty()) {
                throw new GeminiRecognitionException(STATUS_MODEL_FAILED, "Gemini response contains no candidate text", responseBody, null);
            }

            StringBuilder text = new StringBuilder();
            for (JsonNode part : parts) {
                if (part.hasNonNull("text")) {
                    text.append(part.get("text").asText());
                }
            }

            if (!StringUtils.hasText(text)) {
                throw new GeminiRecognitionException(STATUS_MODEL_FAILED, "Gemini response candidate text is empty", responseBody, null);
            }
            return text.toString();
        } catch (JsonProcessingException ex) {
            throw new GeminiRecognitionException(STATUS_MODEL_FAILED, "Gemini response is not valid JSON", responseBody, ex);
        }
    }

    GeminiRecognitionResult parseRecognitionJson(String modelText) {
        String json = cleanJsonText(modelText);
        try {
            GeminiRecognitionResult result = objectMapper.readValue(json, GeminiRecognitionResult.class);
            if (result.getItems() == null) {
                result.setItems(List.of());
            }
            return result;
        } catch (JsonProcessingException ex) {
            throw new GeminiRecognitionException(STATUS_PARSE_FAILED, "Gemini recognition result is not valid JSON", modelText, ex);
        }
    }

    String cleanJsonText(String modelText) {
        if (modelText == null) {
            return "";
        }

        String text = modelText.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```(?:json|JSON)?\\s*", "");
            text = text.replaceFirst("\\s*```$", "");
        }
        return text.trim();
    }
}
