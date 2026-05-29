package com.symptomgraph.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.symptomgraph.config.GeminiProperties;
import com.symptomgraph.dto.GeminiRecognitionResult;
import com.symptomgraph.exception.GeminiRecognitionException;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeminiVisionServiceImplTest {

    private final GeminiVisionServiceImpl service = new GeminiVisionServiceImpl(
            RestClient.builder(),
            new ObjectMapper(),
            new GeminiProperties()
    );

    @Test
    void parseRecognitionJsonSupportsMarkdownCodeFence() {
        String modelText = """
                ```json
                {
                  "platform": "小红书",
                  "context_target": "上下文原文",
                  "original_publish_time": null,
                  "items": [
                    {
                      "comment_index": 1,
                      "raw_content": "评论原文",
                      "tags": ["医疗焦虑", "恐艾"]
                    }
                  ]
                }
                ```
                """;

        GeminiRecognitionResult result = service.parseRecognitionJson(modelText);

        assertThat(result.getPlatform()).isEqualTo("小红书");
        assertThat(result.getContextTarget()).isEqualTo("上下文原文");
        assertThat(result.getOriginalPublishTime()).isNull();
        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getCommentIndex()).isEqualTo(1);
        assertThat(result.getItems().get(0).getRawContent()).isEqualTo("评论原文");
        assertThat(result.getItems().get(0).getTags()).containsExactly("医疗焦虑", "恐艾");
    }

    @Test
    void extractCandidateTextReadsGeminiResponse() {
        String responseBody = """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {"text": "{\\"platform\\":\\"微博\\",\\"items\\":[]}"}
                        ]
                      }
                    }
                  ]
                }
                """;

        String text = service.extractCandidateText(responseBody);

        assertThat(text).isEqualTo("{\"platform\":\"微博\",\"items\":[]}");
    }

    @Test
    void parseRecognitionJsonThrowsParseFailedForInvalidJson() {
        assertThatThrownBy(() -> service.parseRecognitionJson("not json"))
                .isInstanceOf(GeminiRecognitionException.class)
                .extracting("parseStatus")
                .isEqualTo(GeminiVisionServiceImpl.STATUS_PARSE_FAILED);
    }
}
