package com.symptomgraph.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.symptomgraph.config.GeminiProperties;
import com.symptomgraph.exception.GeminiRecognitionException;
import com.symptomgraph.exception.VisionRecognitionException;
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
    public static final String STATUS_PARSE_FAILED = VisionRecognitionJsonParser.STATUS_PARSE_FAILED;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final GeminiProperties properties;
    private final VisionRecognitionJsonParser jsonParser;

    public GeminiVisionServiceImpl(RestClient.Builder restClientBuilder,
                                    ObjectMapper objectMapper,
                                    GeminiProperties properties,
                                    VisionRecognitionJsonParser jsonParser) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.jsonParser = jsonParser;
    }

    @Override
    public String providerName() {
        return "gemini";
    }

    @Override
    public com.symptomgraph.dto.VisionRecognitionResult recognize(byte[] imageBytes, String mimeType) {
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
        com.symptomgraph.dto.VisionRecognitionResult result;
        try {
            result = parseRecognitionJson(modelText);
        } catch (VisionRecognitionException ex) {
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
                                Map.of("text", VisionRecognitionPrompt.PROMPT),
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

    com.symptomgraph.dto.VisionRecognitionResult parseRecognitionJson(String modelText) {
        return jsonParser.parse(modelText);
    }
}
