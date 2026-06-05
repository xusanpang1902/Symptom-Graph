package com.symptomgraph.mq;

import com.symptomgraph.config.CorpusRabbitMqProperties;
import com.symptomgraph.dto.CorpusProcessMessage;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class CorpusProcessMessageProducer {

    private final RabbitTemplate rabbitTemplate;
    private final CorpusRabbitMqProperties properties;

    public CorpusProcessMessageProducer(RabbitTemplate rabbitTemplate, CorpusRabbitMqProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    public void send(CorpusProcessMessage message) {
        // 队列投递说明：这里不把图片字节塞进 MQ，只传递数据库记录 ID 和 OSS 对象信息。
        // 后续 Consumer 会根据 recordId 定位 PROCESSING 记录，并从私有 OSS 下载原图后调用当前的 VisionRecognitionService。
        // 这样可以避免大消息压垮 RabbitMQ，也能继续复用 Gemini/OpenRouter Provider 策略模式。
        rabbitTemplate.convertAndSend(
                properties.getCorpusExchange(),
                properties.getCorpusRoutingKey(),
                message
        );
    }
}
