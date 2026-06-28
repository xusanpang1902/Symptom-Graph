package com.symptomgraph.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.markdown")
public class MarkdownProperties {

    private String outputDir = "obsidian-output";

    private String contentVersion = "model";
}
