package com.example.learningassistant.infra.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ 消息发布实现：配置 app.infra.mq=rabbit 且存在 RabbitMQ 服务时启用。
 * 消费者在业务模块中通过 @RabbitListener 订阅对应 topic 队列。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.infra.mq-mode", havingValue = "rabbit")
public class RabbitMessagePublisher implements MessagePublisher {

    private final RabbitTemplate rabbitTemplate;

    @Override
    public void publish(String topic, String payload) {
        rabbitTemplate.convertAndSend("la.exchange", topic, payload);
        log.info("[MQ-rabbit] topic={} 已发送", topic);
    }

    @Override
    public long pendingCount(String topic) {
        // 生产环境应通过 RabbitAdmin 查询队列深度；骨架阶段返回 -1 表示不可用
        return -1;
    }
}
