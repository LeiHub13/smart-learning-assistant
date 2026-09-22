package com.example.learningassistant.kb.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时的向量索引一致性检查：向量库已迁到 ai-service（Chroma 持久化目录），
 * 但 chunk 正文仍以 Java 侧 t_chunk 为准，两侧可能因上传时索引失败、
 * Chroma 卷被清空或更换 Embedding 模型而不一致。
 *
 * 启动后异步比对 chunk 数与向量数，不一致则通知 ai-service 全量重建。
 * ai-service 可能比 backend 晚就绪，故带重试等待；检查失败只记日志，
 * 不阻塞启动（可随时调用 POST /api/kb/reindex 手动重建）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorIndexWarmUp implements ApplicationRunner {

    private final KbService kbService;

    private static final int MAX_TRIES = 5;
    private static final long RETRY_INTERVAL_MS = 3000L;

    @Override
    public void run(ApplicationArguments args) {
        Thread t = new Thread(this::ensureIndexConsistent, "vector-warmup");
        t.setDaemon(true);
        t.start();
    }

    private void ensureIndexConsistent() {
        int chunks = kbService.chunkCount();
        if (chunks == 0) {
            return;
        }
        for (int i = 1; i <= MAX_TRIES; i++) {
            try {
                Thread.sleep(RETRY_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            long vectors = kbService.vectorCount();
            if (vectors < 0) {
                log.info("ai-service 尚未就绪（第 {}/{} 次），稍后重试向量索引一致性检查", i, MAX_TRIES);
                continue;
            }
            if (vectors == chunks) {
                return;
            }
            log.info("向量索引与 chunk 表不一致（chunk={} 向量={}），通知 ai-service 重建...", chunks, vectors);
            try {
                kbService.reindexFromChunks();
            } catch (Exception e) {
                log.warn("向量索引自动重建失败（可稍后调用 POST /api/kb/reindex 手动重建）: {}", e.getMessage());
            }
            return;
        }
        log.warn("ai-service 未就绪，已跳过启动时的向量索引一致性检查（可调用 POST /api/kb/reindex 手动重建）");
    }
}
