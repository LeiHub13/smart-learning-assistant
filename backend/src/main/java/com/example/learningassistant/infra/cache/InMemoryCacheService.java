package com.example.learningassistant.infra.cache;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存缓存实现（默认）：无 Redis 环境下的降级方案，进程内 ConcurrentHashMap + 惰性过期。
 * 配置 app.infra.cache=memory（或未配置）时启用。
 */
@Component
@ConditionalOnProperty(name = "app.infra.cache-mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryCacheService implements CacheService {

    private record Entry(String value, long expireAt) {
    }

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    @Override
    public void set(String key, String value, Duration ttl) {
        store.put(key, new Entry(value, System.currentTimeMillis() + ttl.toMillis()));
    }

    @Override
    public String get(String key) {
        Entry e = store.get(key);
        if (e == null) {
            return null;
        }
        if (e.expireAt() < System.currentTimeMillis()) {
            store.remove(key);
            return null;
        }
        return e.value();
    }

    @Override
    public Boolean delete(String key) {
        return store.remove(key) != null;
    }

    @Override
    public boolean exists(String key) {
        return get(key) != null;
    }
}
