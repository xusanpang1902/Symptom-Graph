package com.symptomgraph.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.symptomgraph.config.OpenRouterProperties;
import com.symptomgraph.dto.VisionRecognitionOptions;
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

/**
 * OpenRouter 视觉识别 Provider 实现。
 *
 * <p>从架构上看，这个类是应用内部统一视觉识别接口与 OpenRouter Chat Completions API
 * 之间的适配层。它负责把项目的图片识别请求转换为 OpenAI-compatible 的 messages
 * 结构，并把 OpenRouter 返回的 choices/message/content 响应外壳还原为统一识别 JSON。
 * Prompt 内容与识别 JSON 解析仍由共享组件负责，避免不同 Provider 产生不同业务语义。</p>
 */
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
        return recognize(imageBytes, mimeType, null);
    }

    @Override
    public VisionRecognitionResult recognize(byte[] imageBytes, String mimeType, VisionRecognitionOptions options) {
        // 在发起远程模型调用前先校验本地前置条件，避免无效请求进入外部服务。
        // OpenRouter 只是 Provider 适配层，错误状态仍收敛到采集链路统一理解的 MODEL_FAILED。
        if (imageBytes == null || imageBytes.length == 0) {
            throw new VisionRecognitionException(STATUS_MODEL_FAILED, "Image bytes must not be empty");
        }
        if (!StringUtils.hasText(mimeType)) {
            throw new VisionRecognitionException(STATUS_MODEL_FAILED, "Image mimeType must not be blank");
        }
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new VisionRecognitionException(STATUS_MODEL_FAILED, "OpenRouter API key is not configured");
        }

        String providerResponseBody = callOpenRouter(imageBytes, mimeType, resolveModel(options));
        String modelContentText = extractMessageContent(providerResponseBody);
        VisionRecognitionResult recognitionResult;
        try {
            // OpenRouter 使用 Chat Completions 响应结构，但模型正文仍必须符合项目统一的识别结果结构。
            recognitionResult = jsonParser.parse(modelContentText);
        } catch (VisionRecognitionException ex) {
            throw new VisionRecognitionException(ex.getParseStatus(), ex.getMessage(), providerResponseBody, ex);
        }
        recognitionResult.setModelRawResponse(providerResponseBody);
        return recognitionResult;
    }

    private String callOpenRouter(byte[] imageBytes, String mimeType, String model) {
        Map<String, Object> requestBody = buildRequestBody(imageBytes, mimeType, model);
        try {
            // OpenRouter 兼容 OpenAI Chat Completions 协议，请求地址、鉴权头和可选站点信息
            // 都集中在本类中处理，避免泄漏到上层采集链路。
            String providerResponseBody = restClient.post()
                    .uri(buildChatCompletionsUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(this::applyHeaders)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            if (!StringUtils.hasText(providerResponseBody)) {
                throw new VisionRecognitionException(STATUS_MODEL_FAILED, "OpenRouter returned an empty response");
            }
            return providerResponseBody;
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

    private String resolveModel(VisionRecognitionOptions options) {
        if (options != null && StringUtils.hasText(options.getModel())) {
            return options.getModel();
        }
        return properties.getModel();
    }

    private Map<String, Object> buildRequestBody(byte[] imageBytes, String mimeType) {
        return buildRequestBody(imageBytes, mimeType, properties.getModel());
    }

    private Map<String, Object> buildRequestBody(byte[] imageBytes, String mimeType, String model) {
        // OpenRouter 的图片输入使用 image_url content type。本地图片需要编码成 data URL，
        // 而不是 Gemini 那种 inline_data/mime_type/data 三字段结构。
        String dataUrl = "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(imageBytes);
        return Map.of(
                "model", model,
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
        // OpenRouter 使用 Bearer token 鉴权；HTTP-Referer 和 X-Title 是平台推荐的可选元数据，
        // 用于 OpenRouter 侧的来源识别、排行榜或控制台展示，不参与业务解析。
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
        // 配置既允许写基础地址，也允许直接写完整 chat/completions 地址，降低本地配置出错概率。
        if (endpoint.endsWith("/chat/completions")) {
            return endpoint;
        }
        return endpoint + "/chat/completions";
    }

    String normalizeApiKey(String apiKey) {
        // 配置中可能已经包含 Bearer 前缀；setBearerAuth 需要纯 token，这里做一次容错归一化。
        String normalized = apiKey == null ? "" : apiKey.trim();
        if (normalized.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            return normalized.substring("Bearer ".length()).trim();
        }
        return normalized;
    }

    String extractMessageContent(String providerResponseBody) {
        try {
            JsonNode content = objectMapper.readTree(providerResponseBody)
                    .path("choices")
                    .path(0)
                    .path("message")
                    .path("content");
            if (content.isTextual() && StringUtils.hasText(content.asText())) {
                return content.asText();
            }
            // 不同模型或上游 Provider 可能把 message.content 返回为字符串，也可能返回为
            // content parts 数组。数组形态下只拼接 text part，其他模态或元数据不进入 JSON 解析。
            if (content.isArray()) {
                StringBuilder modelContentText = new StringBuilder();
                for (JsonNode part : content) {
                    if (part.hasNonNull("text")) {
                        modelContentText.append(part.get("text").asText());
                    }
                }
                if (StringUtils.hasText(modelContentText)) {
                    return modelContentText.toString();
                }
            }
            throw new VisionRecognitionException(STATUS_MODEL_FAILED, "OpenRouter response contains no message content", providerResponseBody, null);
        } catch (JsonProcessingException ex) {
            throw new VisionRecognitionException(STATUS_MODEL_FAILED, "OpenRouter response is not valid JSON", providerResponseBody, ex);
        }
    }
}
