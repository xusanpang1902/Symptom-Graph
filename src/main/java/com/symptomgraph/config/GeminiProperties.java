package com.symptomgraph.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.gemini")
public class GeminiProperties {

    private String apiKey;

    private String model = "gemini-1.5-flash";

    private String endpoint = "https://generativelanguage.googleapis.com/v1beta";
}
