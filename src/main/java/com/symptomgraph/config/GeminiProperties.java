package com.symptomgraph.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "app.gemini")
public class GeminiProperties {

    private String apiKey;

    private String model = "gemini-1.5-flash";

    private List<String> modelOptions = new ArrayList<>(List.of(
            "gemini-1.5-flash",
            "gemini-2.0-flash",
            "gemini-2.5-flash"
    ));

    private String endpoint = "https://generativelanguage.googleapis.com/v1beta";
}
