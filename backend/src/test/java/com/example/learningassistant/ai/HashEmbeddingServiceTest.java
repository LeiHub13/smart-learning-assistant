package com.example.learningassistant.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HashEmbeddingServiceTest {

    private final HashEmbeddingService svc = new HashEmbeddingService();

    @Test
    void embedReturnsNormalizedVector() {
        float[] v1 = svc.embed("hello world");
        float[] v2 = svc.embed("hello world");
        assertEquals(v1.length, v2.length);
        double norm = 0;
        for (float v : v1) {
            norm += v * v;
        }
        assertTrue(norm > 0.99 && norm < 1.01);
    }

    @Test
    void embedDifferentTextsDiffer() {
        float[] v1 = svc.embed("java");
        float[] v2 = svc.embed("python");
        double dot = 0;
        for (int i = 0; i < v1.length; i++) {
            dot += v1[i] * v2[i];
        }
        assertTrue(dot < 0.99);
    }
}
