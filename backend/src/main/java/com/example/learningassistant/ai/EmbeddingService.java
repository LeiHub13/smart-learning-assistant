package com.example.learningassistant.ai;

import org.springframework.stereotype.Component;

/**
 * 向量化服务抽象：文本 -> 稠密向量。
 * 实现：HashEmbeddingService（离线哈希，默认）；生产可替换为真实 Embedding API。
 */
public interface EmbeddingService {

    float[] embed(String text);
}
