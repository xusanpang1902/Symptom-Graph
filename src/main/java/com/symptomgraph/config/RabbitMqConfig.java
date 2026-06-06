package com.symptomgraph.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CorpusRabbitMqProperties.class)
public class RabbitMqConfig {

    @Bean
    public DirectExchange corpusProcessExchange(CorpusRabbitMqProperties properties) {
        return new DirectExchange(properties.getCorpusExchange(), true, false);
    }

    @Bean
    public Queue corpusProcessQueue(CorpusRabbitMqProperties properties) {
        return new Queue(properties.getCorpusProcessQueue(), true);
    }

    @Bean
    public Binding corpusProcessBinding(Queue corpusProcessQueue,
                                        DirectExchange corpusProcessExchange,
                                        CorpusRabbitMqProperties properties) {
        return BindingBuilder.bind(corpusProcessQueue)
                .to(corpusProcessExchange)
                .with(properties.getCorpusRoutingKey());
    }

    @Bean
    public DirectExchange corpusProcessRetryExchange(CorpusRabbitMqProperties properties) {
        return new DirectExchange(properties.getRetryExchange(), true, false);
    }

    @Bean
    public Queue corpusProcessRetryQueue(CorpusRabbitMqProperties properties) {
        return QueueBuilder.durable(properties.getRetryQueue())
                .ttl(Math.toIntExact(properties.getRetryDelayMillis()))
                .deadLetterExchange(properties.getCorpusExchange())
                .deadLetterRoutingKey(properties.getCorpusRoutingKey())
                .build();
    }

    @Bean
    public Binding corpusProcessRetryBinding(Queue corpusProcessRetryQueue,
                                             DirectExchange corpusProcessRetryExchange,
                                             CorpusRabbitMqProperties properties) {
        return BindingBuilder.bind(corpusProcessRetryQueue)
                .to(corpusProcessRetryExchange)
                .with(properties.getRetryRoutingKey());
    }

    @Bean
    public DirectExchange corpusProcessDeadLetterExchange(CorpusRabbitMqProperties properties) {
        return new DirectExchange(properties.getDeadLetterExchange(), true, false);
    }

    @Bean
    public Queue corpusProcessDeadLetterQueue(CorpusRabbitMqProperties properties) {
        return QueueBuilder.durable(properties.getDeadLetterQueue()).build();
    }

    @Bean
    public Binding corpusProcessDeadLetterBinding(Queue corpusProcessDeadLetterQueue,
                                                  DirectExchange corpusProcessDeadLetterExchange,
                                                  CorpusRabbitMqProperties properties) {
        return BindingBuilder.bind(corpusProcessDeadLetterQueue)
                .to(corpusProcessDeadLetterExchange)
                .with(properties.getDeadLetterRoutingKey());
    }

    @Bean
    public MessageConverter rabbitMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
