package com.symptomgraph.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.vision")
public class VisionProperties {

    private String provider = "gemini";
}
