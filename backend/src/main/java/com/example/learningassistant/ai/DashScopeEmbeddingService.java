package com.example.learningassistant.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * DashScope 通义 Embedding：调用 text-embedding-v3 等模型，返回 1024/1536 维向量。
 * 切换方式：application.yml 中 app.embedding.provider=dashscope 并配置 api-key。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.embedding.provider", havingValue = "dashscope")
public class DashScopeEmbeddingService implements EmbeddingService {

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public DashScopeEmbeddingService(RestTemplate restTemplate,
                                     @Value("${app.embedding.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
                                     @Value("${app.embedding.api-key:}") String apiKey,
                                     @Value("${app.embedding.model:text-embedding-v3}") String model) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return new float[0];
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        Map<String, Object> body = Map.of(
                "model", model,
                "input", Map.of("texts", List.of(text)));
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = restTemplate.postForObject(
                    baseUrl + "/embeddings", new HttpEntity<>(body, headers), Map.class);
            if (resp == null) {
                throw new IllegalStateException("Embedding 接口返回空");
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) resp.get("data");
            if (data == null || data.isEmpty()) {
                throw new IllegalStateException("Embedding 接口未返回向量");
            }
            @SuppressWarnings("unchecked")
            List<Double> vec = (List<Double>) data.get(0).get("embedding");
            float[] result = new float[vec.size()];
            for (int i = 0; i < vec.size(); i++) {
                result[i] = vec.get(i).floatValue();
            }
            return result;
        } catch (Exception e) {
            log.error("DashScope Embedding 调用失败: {}", e.getMessage());
            throw new IllegalStateException("Embedding 调用失败: " + e.getMessage(), e);
        }
    }
}
