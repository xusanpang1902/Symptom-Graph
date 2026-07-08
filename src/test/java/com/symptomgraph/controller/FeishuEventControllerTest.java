package com.symptomgraph.controller;

import com.symptomgraph.config.FeishuProperties;
import com.symptomgraph.integration.feishu.FeishuEventParser;
import com.symptomgraph.integration.feishu.FeishuImageIngestionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FeishuEventController.class)
@Import({FeishuProperties.class, FeishuEventParser.class})
@TestPropertySource(properties = {
        "app.feishu.enabled=true",
        "app.feishu.verification-token=test-token"
})
class FeishuEventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FeishuImageIngestionService imageIngestionService;

    @Test
    void eventsReturnsChallengeForUrlVerification() throws Exception {
        mockMvc.perform(post("/api/v1/feishu/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "url_verification",
                                  "token": "test-token",
                                  "challenge": "challenge-value"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challenge").value("challenge-value"));
    }

    @Test
    void eventsDispatchesImageMessage() throws Exception {
        mockMvc.perform(post("/api/v1/feishu/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "schema": "2.0",
                                  "header": {
                                    "event_id": "event_1",
                                    "event_type": "im.message.receive_v1",
                                    "token": "test-token"
                                  },
                                  "event": {
                                    "sender": {
                                      "sender_id": {
                                        "open_id": "ou_1"
                                      }
                                    },
                                    "message": {
                                      "message_id": "om_1",
                                      "chat_id": "oc_1",
                                      "message_type": "image",
                                      "content": "{\\"image_key\\":\\"img_1\\"}"
                                    }
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<com.symptomgraph.integration.feishu.FeishuImageMessageEvent> eventCaptor =
                ArgumentCaptor.forClass(com.symptomgraph.integration.feishu.FeishuImageMessageEvent.class);
        verify(imageIngestionService).ingest(eventCaptor.capture());
        assertThat(eventCaptor.getValue().eventId()).isEqualTo("event_1");
        assertThat(eventCaptor.getValue().messageId()).isEqualTo("om_1");
        assertThat(eventCaptor.getValue().chatId()).isEqualTo("oc_1");
        assertThat(eventCaptor.getValue().senderId()).isEqualTo("ou_1");
        assertThat(eventCaptor.getValue().imageKey()).isEqualTo("img_1");
    }
}
