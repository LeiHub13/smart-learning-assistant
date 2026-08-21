package com.example.learningassistant.infra.mq;

/**
 * 消息发布抽象：异步任务（文档索引、批量生成、通知、缓存失效广播）。
 * 实现可降级切换（memory / rabbit）。
 */
public interface MessagePublisher {

    void publish(String topic, String payload);

    /** 待处理消息数（内存实现为队列长度，Rabbit 实现为绑定队列积压数） */
    long pendingCount(String topic);
}
