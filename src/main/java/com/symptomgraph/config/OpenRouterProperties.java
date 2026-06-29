package com.symptomgraph.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "app.openrouter")
public class OpenRouterProperties {

    private String apiKey;

    private String model = "qwen/qwen3.6-flash";

    private List<String> modelOptions = new ArrayList<>(List.of(
            "qwen/qwen3.6-flash",
            "qwen/qwen2.5-vl-72b-instruct",
            "google/gemini-2.0-flash-001"
    ));

    private String endpoint = "https://openrouter.ai/api/v1";

    private String referer;

    private String title = "Symptom-Graph";
}
