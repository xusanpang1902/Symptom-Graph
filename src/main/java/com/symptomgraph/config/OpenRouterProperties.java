package com.symptomgraph.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.openrouter")
public class OpenRouterProperties {

    private String apiKey;

    private String model = "qwen/qwen3.6-flash";

    private String endpoint = "https://openrouter.ai/api/v1";

    private String referer;

    private String title = "Symptom-Graph";
}
