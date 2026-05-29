package com.symptomgraph.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MarkdownProperties.class)
public class MarkdownConfig {
}
