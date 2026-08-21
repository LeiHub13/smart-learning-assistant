package com.example.learningassistant.web.controller;

import com.example.learningassistant.ai.ChatModelFactory;
import com.example.learningassistant.common.ApiResponse;
import com.example.learningassistant.infra.cache.CacheService;
import com.example.learningassistant.infra.mq.MessagePublisher;
import com.example.learningassistant.infra.storage.FileStorage;
import com.example.learningassistant.infra.vector.VectorStore;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 健康检查与基础设施状态（答辩演示：直观展示各中间件当前启用的是哪个实现）。
 */
@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
public class HealthController {

    private final ChatModelFactory modelFactory;
    private final CacheService cacheService;
    private final VectorStore vectorStore;
    private final FileStorage fileStorage;
    private final MessagePublisher messagePublisher;

    @GetMapping
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(Map.of(
                "app", "learning-assistant-v2",
                "llmProvider", modelFactory.get().provider(),
                "cache", cacheService.getClass().getSimpleName(),
                "vectorStore", vectorStore.getClass().getSimpleName() + "(" + vectorStore.size() + ")",
                "fileStorage", fileStorage.getClass().getSimpleName(),
                "mq", messagePublisher.getClass().getSimpleName(),
                "status", "UP"));
    }
}
