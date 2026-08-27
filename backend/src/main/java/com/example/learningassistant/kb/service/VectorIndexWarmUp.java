package com.example.learningassistant.kb.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 内存向量库热身：仅 app.infra.vector-mode=memory（默认）时生效。
 *
 * 内存版向量库随应用重启丢失，而 chunk 元数据在 H2/MySQL 中持久存在——
 * 若不重建，重启后 RAG 检索会静默返回空结果。启动后异步检测到
 * 「chunk 表有数据但向量库为空」时自动从 t_chunk 重建，修复该问题。
 * Milvus 等持久化向量库无需此步骤。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.infra.vector-mode", havingValue = "memory", matchIfMissing = true)
public class VectorIndexWarmUp implements ApplicationRunner {

    private final KbService kbService;

    @Override
    public void run(ApplicationArguments args) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(3000);
                int chunks = kbService.chunkCount();
                if (chunks > 0 && kbService.vectorCount() == 0) {
                    log.info("检测到 chunk 表有 {} 条数据但内存向量库为空，开始重建索引...", chunks);
                    kbService.reindexFromChunks();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("内存向量库自动重建失败（可稍后调用 POST /api/kb/reindex 手动重建）: {}", e.getMessage());
            }
        }, "vector-warmup");
        t.setDaemon(true);
        t.start();
    }
}
