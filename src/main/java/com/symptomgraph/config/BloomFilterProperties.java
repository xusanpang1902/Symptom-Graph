package com.symptomgraph.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.bloom")
public class BloomFilterProperties {

    private boolean enabled = false;

    private String name = "symptom_graph_hash_bloom";

    private long expectedInsertions = 100_000L;

    private double falseProbability = 0.01D;
}
