package com.example.learningassistant.ai;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于字符 n-gram 哈希的演示用向量化（128 维），离线可用、零外部依赖。
 * 生产环境建议替换为真实 Embedding 服务（如 text-embedding-3-small）以提升检索质量。
 */
@Service
public class HashEmbeddingService implements EmbeddingService {

    private static final int DIM = 128;

    @Override
    public float[] embed(String text) {
        float[] vec = new float[DIM];
        if (text == null || text.isBlank()) {
            return vec;
        }
        String norm = text.toLowerCase().replaceAll("\\s+", "");
        List<String> grams = new ArrayList<>();
        for (int i = 0; i < norm.length(); i++) {
            grams.add(String.valueOf(norm.charAt(i)));
            if (i + 1 < norm.length()) {
                grams.add(norm.substring(i, i + 2));
            }
        }
        for (String g : grams) {
            int h = Math.abs(g.hashCode());
            vec[h % DIM] += 1f;
        }
        float norm2 = 0f;
        for (float v : vec) {
            norm2 += v * v;
        }
        norm2 = (float) Math.sqrt(norm2);
        if (norm2 > 0) {
            for (int i = 0; i < DIM; i++) {
                vec[i] /= norm2;
            }
        }
        return vec;
    }
}
