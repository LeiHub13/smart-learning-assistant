package com.example.learningassistant.infra.vector;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存向量存储（默认降级）：chunkId -> 向量，全量余弦扫描。
 * 生产环境替换为 MilvusVectorStore（配置 app.infra.vector=milvus）。
 */
@Component
@ConditionalOnProperty(name = "app.infra.vector-mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryVectorStore implements VectorStore {

    private final Map<Long, float[]> vectors = new ConcurrentHashMap<>();

    @Override
    public void put(long id, float[] vector) {
        vectors.put(id, vector);
    }

    @Override
    public void remove(long id) {
        vectors.remove(id);
    }

    @Override
    public void clear() {
        vectors.clear();
    }

    @Override
    public List<ScoredId> search(float[] query, int topK) {
        List<ScoredId> results = new ArrayList<>();
        for (Map.Entry<Long, float[]> e : vectors.entrySet()) {
            results.add(new ScoredId(e.getKey(), cosine(query, e.getValue())));
        }
        results.sort((a, b) -> Double.compare(b.score(), a.score()));
        int limit = Math.min(topK, results.size());
        return results.subList(0, limit);
    }

    @Override
    public int size() {
        return vectors.size();
    }

    private float cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            dot += a[i] * b[i];
        }
        return (float) dot;
    }
}
