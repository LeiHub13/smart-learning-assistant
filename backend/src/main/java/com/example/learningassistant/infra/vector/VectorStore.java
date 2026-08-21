package com.example.learningassistant.infra.vector;

import java.util.List;

/**
 * 向量库抽象：知识库语义检索。实现可降级切换（memory / milvus / pgvector）。
 */
public interface VectorStore {

    void put(long id, float[] vector);

    void remove(long id);

    void clear();

    /** 余弦相似度检索 Top-K */
    List<ScoredId> search(float[] query, int topK);

    int size();

    record ScoredId(long id, double score) {
    }
}
