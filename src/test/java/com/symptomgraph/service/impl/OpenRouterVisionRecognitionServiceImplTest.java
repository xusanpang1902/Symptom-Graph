package com.symptomgraph.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.symptomgraph.config.OpenRouterProperties;
import com.symptomgraph.exception.VisionRecognitionException;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenRouterVisionRecognitionServiceImplTest {

    private final OpenRouterVisionRecognitionServiceImpl service = new OpenRouterVisionRecognitionServiceImpl(
            RestClient.builder(),
            new ObjectMapper(),
            new OpenRouterProperties(),
            new VisionRecognitionJsonParser(new ObjectMapper())
    );

    @Test
    void extractMessageContentReadsStringContent() {
        String responseBody = """
                {
                  "choices": [
                    {
                      "message": {
                        "content": "{\\\"platform\\\":\\\"小红书\\\",\\\"items\\\":[]}"
                      }
                    }
                  ]
                }
                """;

        String content = service.extractMessageContent(responseBody);

        assertThat(content).isEqualTo("{\"platform\":\"小红书\",\"items\":[]}");
    }

    @Test
    void extractMessageContentReadsArrayTextContent() {
        String responseBody = """
                {
                  "choices": [
                    {
                      "message": {
                        "content": [
                          {"type": "text", "text": "{\\\"platform\\\":"},
                          {"type": "text", "text": "\\\"微博\\\",\\\"items\\\":[]}"}
                        ]
                      }
                    }
                  ]
                }
                """;

        String content = service.extractMessageContent(responseBody);

        assertThat(content).isEqualTo("{\"platform\":\"微博\",\"items\":[]}");
    }

    @Test
    void extractMessageContentThrowsModelFailedForMissingContent() {
        assertThatThrownBy(() -> service.extractMessageContent("{\"choices\":[{\"message\":{}}]}"))
                .isInstanceOf(VisionRecognitionException.class)
                .extracting("parseStatus")
                .isEqualTo(OpenRouterVisionRecognitionServiceImpl.STATUS_MODEL_FAILED);
    }

    @Test
    void buildChatCompletionsUrlAcceptsFullEndpoint() {
        OpenRouterProperties properties = new OpenRouterProperties();
        properties.setEndpoint("https://openrouter.ai/api/v1/chat/completions");
        OpenRouterVisionRecognitionServiceImpl service = new OpenRouterVisionRecognitionServiceImpl(
                RestClient.builder(),
                new ObjectMapper(),
                properties,
                new VisionRecognitionJsonParser(new ObjectMapper())
        );

        assertThat(service.buildChatCompletionsUrl()).isEqualTo("https://openrouter.ai/api/v1/chat/completions");
    }

    @Test
    void normalizeApiKeyStripsBearerPrefix() {
        assertThat(service.normalizeApiKey("Bearer sk-or-test")).isEqualTo("sk-or-test");
        assertThat(service.normalizeApiKey("sk-or-test")).isEqualTo("sk-or-test");
    }
}
