package com.symptomgraph.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.rabbitmq")
public class CorpusRabbitMqProperties {

    private String corpusExchange = "corpus.process.exchange";

    private String corpusProcessQueue = "corpus.process.queue";

    private String corpusRoutingKey = "corpus.process";

    private String retryExchange = "corpus.process.retry.exchange";

    private String retryQueue = "corpus.process.retry.queue";

    private String retryRoutingKey = "corpus.process.retry";

    private String deadLetterExchange = "corpus.process.dlx.exchange";

    private String deadLetterQueue = "corpus.process.dlq";

    private String deadLetterRoutingKey = "corpus.process.dlq";

    private int maxRetryAttempts = 3;

    private long retryDelayMillis = 10_000L;
}
