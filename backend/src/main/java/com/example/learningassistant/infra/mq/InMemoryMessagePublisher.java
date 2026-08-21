package com.example.learningassistant.infra.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存消息发布（默认降级）：进程内主题队列，供演示与单机开发使用。
 * 生产环境切换 RabbitMessagePublisher（配置 app.infra.mq=rabbit）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.infra.mq-mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryMessagePublisher implements MessagePublisher {

    private final Map<String, List<String>> queues = new ConcurrentHashMap<>();

    @Override
    public void publish(String topic, String payload) {
        queues.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(payload);
        log.info("[MQ-memory] topic={} 消息入队，积压={}", topic, pendingCount(topic));
    }

    @Override
    public long pendingCount(String topic) {
        List<String> q = queues.get(topic);
        return q == null ? 0 : q.size();
    }
}
