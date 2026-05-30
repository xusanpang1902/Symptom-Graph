package com.symptomgraph.service.impl;

import com.symptomgraph.config.VisionProperties;
import com.symptomgraph.dto.VisionRecognitionResult;
import com.symptomgraph.exception.VisionRecognitionException;
import com.symptomgraph.service.VisionRecognitionProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfiguredVisionRecognitionServiceTest {

    @Test
    void recognizeRoutesToConfiguredProvider() {
        VisionProperties properties = new VisionProperties();
        properties.setProvider("openrouter");
        StubProvider provider = new StubProvider("openrouter");
        ConfiguredVisionRecognitionService service = new ConfiguredVisionRecognitionService(properties, List.of(provider));

        VisionRecognitionResult result = service.recognize("image".getBytes(), "image/png");

        assertThat(result.getPlatform()).isEqualTo("stub-openrouter");
    }

    @Test
    void recognizeThrowsForUnsupportedProvider() {
        VisionProperties properties = new VisionProperties();
        properties.setProvider("missing");
        ConfiguredVisionRecognitionService service = new ConfiguredVisionRecognitionService(properties, List.of(new StubProvider("openrouter")));

        assertThatThrownBy(() -> service.recognize("image".getBytes(), "image/png"))
                .isInstanceOf(VisionRecognitionException.class)
                .extracting("parseStatus")
                .isEqualTo(ConfiguredVisionRecognitionService.STATUS_MODEL_FAILED);
    }

    private static class StubProvider implements VisionRecognitionProvider {

        private final String providerName;

        private StubProvider(String providerName) {
            this.providerName = providerName;
        }

        @Override
        public String providerName() {
            return providerName;
        }

        @Override
        public VisionRecognitionResult recognize(byte[] imageBytes, String mimeType) {
            VisionRecognitionResult result = new VisionRecognitionResult();
            result.setPlatform("stub-" + providerName);
            return result;
        }
    }
}
