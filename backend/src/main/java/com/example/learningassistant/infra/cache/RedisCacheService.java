package com.example.learningassistant.infra.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 缓存实现：配置 app.infra.cache=redis 且存在 Redis 服务时启用。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.infra.cache-mode", havingValue = "redis")
public class RedisCacheService implements CacheService {

    private final StringRedisTemplate redisTemplate;

    @Override
    public void set(String key, String value, Duration ttl) {
        redisTemplate.opsForValue().set(key, value, ttl);
    }

    @Override
    public String get(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    @Override
    public Boolean delete(String key) {
        return redisTemplate.delete(key);
    }

    @Override
    public boolean exists(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }
}
