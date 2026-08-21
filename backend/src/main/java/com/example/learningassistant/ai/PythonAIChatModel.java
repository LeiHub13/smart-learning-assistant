package com.example.learningassistant.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 基于 Python langchain 的 AI 适配器：Java 骨架通过 HTTP 调用 ai-service（FastAPI）。
 *
 * 场景映射（从 system 消息中的标记解析）：
 *   RAG_QA / FREE -> /ai/stream（SSE 流式，带会话记忆）
 *   GEN_LECTURE / GEN_QUESTIONS / REVIEW_SUBJECTIVE / ADVICE -> /ai/complete
 * 知识库资料：【知识库资料】...【资料结束】 -> chunks 传给 Python 侧组装上下文。
 * 会话标识：system 中的 SESSION_ID:xxx 用于 Python 端按会话持久化记忆。
 *
 * Agent 模式：app.model.agent-enabled=true 时，RAG_QA / FREE 升级为 agent 场景，
 * Python 侧跑 ReAct 循环（模型自主调用 list_material_topics / search_materials 工具），
 * 接口与调用方完全不变。
 */
@Slf4j
@Component
public class PythonAIChatModel implements ChatModel {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final boolean agentEnabled;

    public PythonAIChatModel(RestTemplate restTemplate,
                             @Value("${app.model.python-base-url:http://localhost:8000}") String baseUrl,
                             @Value("${app.model.agent-enabled:false}") boolean agentEnabled) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.agentEnabled = agentEnabled;
    }

    @Override
    public String provider() {
        return "python-langchain";
    }

    @Override
    public String complete(List<AIChatMessage> messages) {
        RequestPayload payload = toPayload(messages);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = restTemplate.postForObject(
                    baseUrl + "/ai/complete", new HttpEntity<>(payload.toMap(), headers), Map.class);
            if (resp == null) {
                throw new IllegalStateException("ai-service 返回空响应");
            }
            return String.valueOf(resp.get("content"));
        } catch (Exception e) {
            throw new IllegalStateException("调用 ai-service 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void stream(List<AIChatMessage> messages, Consumer<String> onDelta, Runnable onDone, Consumer<Throwable> onError) {
        RequestPayload payload = toPayload(messages);
        Thread streamThread = new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) URI.create(baseUrl + "/ai/stream").toURL().openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(300_000);
                conn.getOutputStream().write(MAPPER.writeValueAsBytes(payload.toMap()));

                if (conn.getResponseCode() != 200) {
                    String err = new String(conn.getErrorStream() == null ? new byte[0]
                            : conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                    throw new IllegalStateException("ai-service 返回 " + conn.getResponseCode() + ": " + err);
                }
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if (data.isEmpty()) {
                        continue;
                    }
                    try {
                        Map<String, Object> d = MAPPER.readValue(data, Map.class);
                        if (d.containsKey("delta")) {
                            onDelta.accept(String.valueOf(d.get("delta")));
                        } else if (Boolean.TRUE.equals(d.get("done"))) {
                            break;
                        } else if (d.containsKey("error")) {
                            throw new IllegalStateException("ai-service: " + d.get("error"));
                        }
                    } catch (java.io.IOException je) {
                        log.warn("SSE 行解析跳过: {}", data);
                    }
                }
                onDone.run();
            } catch (Exception e) {
                onError.accept(e);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }, "ai-stream-" + System.currentTimeMillis());
        streamThread.setDaemon(true);
        streamThread.start();
    }

    private RequestPayload toPayload(List<AIChatMessage> messages) {
        String system = messages.stream()
                .filter(m -> "system".equals(m.role()))
                .map(AIChatMessage::content)
                .findFirst().orElse("");
        String lastUser = "";
        for (AIChatMessage m : messages) {
            if ("user".equals(m.role())) {
                lastUser = m.content();
            }
        }

        String scene;
        if (system.contains("RAG_QA")) {
            scene = "rag_qa";
        } else if (system.contains("FREE")) {
            scene = "free";
        } else if (system.contains("GEN_LECTURE")) {
            scene = "lecture";
        } else if (system.contains("GEN_QUESTIONS")) {
            scene = "questions";
        } else if (system.contains("REVIEW_SUBJECTIVE")) {
            scene = "review";
        } else if (system.contains("ADVICE")) {
            scene = "advice";
        } else {
            scene = "free";
        }
        // Agent 模式：答疑类场景升级为 ReAct Agent（Python 侧自主调工具），其余场景不变
        if (agentEnabled && ("rag_qa".equals(scene) || "free".equals(scene))) {
            scene = "agent";
        }
        return new RequestPayload(scene, lastUser, extractChunks(system), extractSessionId(system));
    }

    private List<String> extractChunks(String system) {
        int s = system.indexOf("【知识库资料】");
        int e = system.indexOf("【资料结束】");
        if (s < 0 || e <= s) {
            return null;
        }
        String ctx = system.substring(s + "【知识库资料】".length(), e).trim();
        if (ctx.isEmpty() || ctx.contains("暂无相关资料")) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        for (String line : ctx.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) {
                lines.add(t.replaceFirst("^\\[\\d+\\]\\s*", ""));
            }
        }
        return lines.isEmpty() ? null : lines;
    }

    private String extractSessionId(String system) {
        int s = system.indexOf("SESSION_ID:");
        if (s < 0) {
            return null;
        }
        String rest = system.substring(s + "SESSION_ID:".length());
        int j = rest.indexOf('\n');
        String id = (j > 0 ? rest.substring(0, j) : rest).trim();
        return id.isEmpty() ? null : id;
    }

    private record RequestPayload(String scene, String question, List<String> chunks, String sessionId) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("scene", scene);
            m.put("question", question);
            if (chunks != null) {
                m.put("chunks", chunks);
            }
            if (sessionId != null) {
                m.put("sessionId", sessionId);
            }
            return m;
        }
    }
}