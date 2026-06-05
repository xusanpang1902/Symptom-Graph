package com.symptomgraph.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.rabbitmq")
public class CorpusRabbitMqProperties {

    private String corpusExchange = "corpus.process.exchange";

    private String corpusProcessQueue = "corpus.process.queue";

    private String corpusRoutingKey = "corpus.process";
}
