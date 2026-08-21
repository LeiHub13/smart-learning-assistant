package com.example.learningassistant.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 兼容 OpenAI 协议的大模型适配器（OpenAI / 通义千问 / DeepSeek / 智谱 等均支持）。
 * 通过 application.yml 的 app.model 配置接入。
 *
 * 注：stream() 当前为"模拟流式"（先完整获取再切分吐出），
 * 生产环境应改为 WebClient 直读厂商 /chat/completions 的 text/event-stream（见架构文档 §7.1）。
 */
@Component
@Slf4j
public class OpenAiCompatibleChatModel implements ChatModel {

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final double temperature;

    public OpenAiCompatibleChatModel(RestTemplate restTemplate,
                                     @Value("${app.model.base-url:https://api.openai.com/v1}") String baseUrl,
                                     @Value("${app.model.api-key:}") String apiKey,
                                     @Value("${app.model.model-name:gpt-4o-mini}") String modelName,
                                     @Value("${app.model.temperature:0.7}") double temperature) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.temperature = temperature;
    }

    @Override
    public String provider() {
        return "openai-compatible";
    }

    @Override
    public String complete(List<AIChatMessage> messages) {
        Map<String, Object> body = Map.of(
                "model", modelName,
                "messages", messages.stream().map(m -> Map.of("role", m.role(), "content", m.content())).toList(),
                "temperature", temperature);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = restTemplate.postForObject(
                    baseUrl + "/chat/completions", new HttpEntity<>(body, headers), Map.class);
            return extractContent(resp);
        } catch (Exception e) {
            throw new IllegalStateException("调用大模型失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void stream(List<AIChatMessage> messages, Consumer<String> onDelta, Runnable onDone, Consumer<Throwable> onError) {
        try {
            String answer = complete(messages);
            for (String ch : answer.split("")) {
                onDelta.accept(ch);
            }
            onDone.run();
        } catch (Exception e) {
            onError.accept(e);
        }
    }

    @SuppressWarnings("unchecked")
    private String extractContent(Map<String, Object> resp) {
        if (resp == null) {
            return "";
        }
        List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.get("choices");
        if (choices == null || choices.isEmpty()) {
            return "";
        }
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        return message == null ? "" : String.valueOf(message.get("content"));
    }
}
