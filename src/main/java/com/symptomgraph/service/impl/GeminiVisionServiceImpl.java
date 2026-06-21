package com.symptomgraph.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.symptomgraph.config.GeminiProperties;
import com.symptomgraph.dto.VisionRecognitionResult;
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

/**
 * Gemini 视觉识别 Provider 实现。
 *
 * <p>从架构上看，这个类是应用内部统一视觉识别接口与 Gemini generateContent HTTP API
 * 之间的适配层。它只负责 Gemini 特有的请求组装、响应外壳提取和异常归一化；
 * Prompt 内容与识别 JSON 解析交给共享组件处理，保证 Gemini 与其他 Provider
 * 在业务语义上保持一致。</p>
 */
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
    public VisionRecognitionResult recognize(byte[] imageBytes, String mimeType) {
        // 在发起远程模型调用前先校验本地前置条件，避免无效请求进入外部服务。
        // 这些错误统一归类为模型阶段失败，便于采集链路复用同一套重试和失败处理逻辑。
        if (imageBytes == null || imageBytes.length == 0) {
            throw new GeminiRecognitionException(STATUS_MODEL_FAILED, "Image bytes must not be empty");
        }
        if (!StringUtils.hasText(mimeType)) {
            throw new GeminiRecognitionException(STATUS_MODEL_FAILED, "Image mimeType must not be blank");
        }
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new GeminiRecognitionException(STATUS_MODEL_FAILED, "Gemini API key is not configured");
        }

        String providerResponseBody = callGemini(imageBytes, mimeType);
        String modelContentText = extractCandidateText(providerResponseBody);
        VisionRecognitionResult recognitionResult;
        try {
            // 解析器保持 Provider 无关。Gemini 可以有自己的响应外壳，
            // 但提取出的模型正文必须符合采集链路统一使用的识别结果结构。
            recognitionResult = parseRecognitionJson(modelContentText);
        } catch (VisionRecognitionException ex) {
            throw new GeminiRecognitionException(ex.getParseStatus(), ex.getMessage(), providerResponseBody, ex);
        }
        recognitionResult.setModelRawResponse(providerResponseBody);
        return recognitionResult;
    }

    private String callGemini(byte[] imageBytes, String mimeType) {
        Map<String, Object> requestBody = buildRequestBody(imageBytes, mimeType);

        try {
            // Gemini 的 API key 通过 query 参数传递，因此 URL 拼装集中在
            // buildGenerateContentUrl() 中处理。
            String providerResponseBody = restClient.post()
                    .uri(buildGenerateContentUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            if (!StringUtils.hasText(providerResponseBody)) {
                throw new GeminiRecognitionException(STATUS_MODEL_FAILED, "Gemini returned an empty response");
            }
            return providerResponseBody;
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

        // Gemini 要求图片以内联 base64 的形式和 Prompt 放在同一组 content parts 中。
        // temperature=0 用于降低输出随机性，response_mime_type 要求模型尽量返回 JSON。
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

    String extractCandidateText(String providerResponseBody) {
        try {
            JsonNode root = objectMapper.readTree(providerResponseBody);
            JsonNode parts = root.path("candidates").path(0).path("content").path("parts");
            if (!parts.isArray() || parts.isEmpty()) {
                throw new GeminiRecognitionException(STATUS_MODEL_FAILED, "Gemini response contains no candidate text", providerResponseBody, null);
            }

            // Gemini 可能把同一个候选结果拆成多个 text part，解析前需要先拼接。
            StringBuilder modelContentText = new StringBuilder();
            for (JsonNode part : parts) {
                if (part.hasNonNull("text")) {
                    modelContentText.append(part.get("text").asText());
                }
            }

            if (!StringUtils.hasText(modelContentText)) {
                throw new GeminiRecognitionException(STATUS_MODEL_FAILED, "Gemini response candidate text is empty", providerResponseBody, null);
            }
            return modelContentText.toString();
        } catch (JsonProcessingException ex) {
            throw new GeminiRecognitionException(STATUS_MODEL_FAILED, "Gemini response is not valid JSON", providerResponseBody, ex);
        }
    }

    VisionRecognitionResult parseRecognitionJson(String modelContentText) {
        return jsonParser.parse(modelContentText);
    }
}
