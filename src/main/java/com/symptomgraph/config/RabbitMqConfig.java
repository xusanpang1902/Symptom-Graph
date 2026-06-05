package com.symptomgraph.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
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
    public MessageConverter rabbitMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
