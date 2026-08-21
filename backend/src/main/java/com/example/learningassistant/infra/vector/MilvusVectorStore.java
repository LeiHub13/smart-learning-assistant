package com.example.learningassistant.infra.vector;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Milvus 向量库实现占位：配置 app.infra.vector=milvus 时启用。
 * 接入方式：milvus-sdk-java 客户端 + 集合管理（维度需与 EmbeddingService 对齐），
 * 检索用 search(collection, queryVector, topK)。当前为骨架占位，业务接入后实现。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.infra.vector-mode", havingValue = "milvus")
public class MilvusVectorStore implements VectorStore {

    @Override
    public void put(long id, float[] vector) {
        throw new UnsupportedOperationException("Milvus 接入待实现：请在 la-infra 引入 milvus-sdk-java 并实现本类");
    }

    @Override
    public void remove(long id) {
        throw new UnsupportedOperationException("Milvus 接入待实现");
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException("Milvus 接入待实现");
    }

    @Override
    public List<ScoredId> search(float[] query, int topK) {
        throw new UnsupportedOperationException("Milvus 接入待实现");
    }

    @Override
    public int size() {
        throw new UnsupportedOperationException("Milvus 接入待实现");
    }
}
