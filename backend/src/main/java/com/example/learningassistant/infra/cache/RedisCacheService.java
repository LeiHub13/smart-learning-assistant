package com.example.learningassistant.infra.cache;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 缓存实现：配置 app.infra.cache-mode=redis 时启用。
 * 不做内存降级：启动时强制连通性校验，Redis 不可达直接拒绝启动（fail-fast）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.infra.cache-mode", havingValue = "redis")
public class RedisCacheService implements CacheService {

    private final StringRedisTemplate redisTemplate;

    @PostConstruct
    void checkConnectivity() {
        try {
            redisTemplate.getConnectionFactory().getConnection().ping();
            log.info("Redis 连通性校验通过（cache-mode=redis，无降级路径）");
        } catch (Exception e) {
            throw new IllegalStateException(
                    "app.infra.cache-mode=redis 但 Redis 不可达（本实现不做内存降级）。"
                  + "请确认 Redis 服务已启动且 spring.data.redis 配置正确：" + e.getMessage(), e);
        }
    }

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
