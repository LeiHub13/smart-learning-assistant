package com.example.learningassistant.infra.cache;

import java.time.Duration;

/**
 * 缓存抽象：业务代码面向该接口编程，实现可降级切换（memory / redis）。
 */
public interface CacheService {

    void set(String key, String value, Duration ttl);

    String get(String key);

    Boolean delete(String key);

    boolean exists(String key);
}
