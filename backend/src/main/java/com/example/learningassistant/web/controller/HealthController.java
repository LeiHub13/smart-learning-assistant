package com.example.learningassistant.web.controller;

import com.example.learningassistant.ai.ChatModelFactory;
import com.example.learningassistant.ai.PythonRagClient;
import com.example.learningassistant.common.ApiResponse;
import com.example.learningassistant.infra.cache.CacheService;
import com.example.learningassistant.infra.mq.MessagePublisher;
import com.example.learningassistant.infra.storage.FileStorage;
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
    private final PythonRagClient ragClient;
    private final FileStorage fileStorage;
    private final MessagePublisher messagePublisher;

    @GetMapping
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(Map.of(
                "app", "learning-assistant-v2",
                "llmProvider", modelFactory.get().provider(),
                "cache", cacheService.getClass().getSimpleName(),
                "rag", ragStatus(),
                "fileStorage", fileStorage.getClass().getSimpleName(),
                "mq", messagePublisher.getClass().getSimpleName(),
                "status", "UP"));
    }

    /** 向量库已迁到 ai-service（Chroma）：展示 embedding provider 与向量条数，不可达时给出原因。 */
    private String ragStatus() {
        try {
            Map<String, Object> s = ragClient.stats();
            return "ai-service-chroma(" + s.get("provider") + ":" + s.get("model")
                    + ", vectors=" + s.get("vectors") + ")";
        } catch (Exception e) {
            return "ai-service(不可用)";
        }
    }
}
