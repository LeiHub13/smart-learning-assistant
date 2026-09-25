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
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 基于 Python langchain 的 AI 适配器：Java 骨架通过 HTTP 调用 ai-service（FastAPI）。
 *
 * 场景映射（从 system 消息中的标记解析）：
 *   RAG_QA / FREE -> /ai/stream（SSE 流式，带会话记忆）
 *   GEN_LECTURE / GEN_QUESTIONS / REVIEW_SUBJECTIVE / ADVICE -> /ai/complete
 * 知识库答疑：system 中的 KB_ID 随请求传给 Python，检索链路（查询改写 -> 向量召回 -> 重排）
 * 全在 ai-service 内完成，引用编号 sources 由流式 done 事件回传（Java 落库）。
 * 检索范围：system 中的 KB_IDS（本课程全部知识库 id 逗号串）随请求下发，缺省表示只搜 KB_ID 单库。
 * 选中知识库：system 中的 KB_NAME / KB_SCOPE（名称与范围）透传给 Python 拼进系统提示，
 * 让 Agent 能回答「当前选中的是哪个知识库」。
 * 学情说明：system 中的 NOTE（如薄弱知识点）拼进 Python 侧系统提示。
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
    public void stream(List<AIChatMessage> messages, Consumer<String> onDelta, Consumer<String> onDone, Consumer<Throwable> onError) {
        RequestPayload payload = toPayload(messages);
        Thread streamThread = new Thread(() -> {
            HttpURLConnection conn = null;
            String sources = "";
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
                            Object s = d.get("sources");
                            sources = s == null ? "" : String.valueOf(s);
                            break;
                        } else if (d.containsKey("error")) {
                            throw new IllegalStateException("ai-service: " + d.get("error"));
                        }
                    } catch (java.io.IOException je) {
                        log.warn("SSE 行解析跳过: {}", data);
                    }
                }
                onDone.accept(sources);
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
        } else if (system.contains("PLAN")) {
            scene = "plan";
        } else if (system.contains("REPORT")) {
            scene = "report";
        } else {
            scene = "free";
        }
        // Agent 模式：答疑类场景升级为 ReAct Agent（Python 侧自主调工具），其余场景不变
        if (agentEnabled && ("rag_qa".equals(scene) || "free".equals(scene))) {
            scene = "agent";
        }
        return new RequestPayload(scene, lastUser, extractMarker(system, "SESSION_ID"),
                extractMarker(system, "USER_ID"), extractMarker(system, "COURSE_ID"),
                extractMarker(system, "KB_ID"), extractMarker(system, "KB_IDS"), extractMarker(system, "NOTE"),
                extractMarker(system, "KB_NAME"), extractMarker(system, "KB_SCOPE"));
    }

    private String extractMarker(String system, String key) {
        String prefix = key + ":";
        int s = system.indexOf(prefix);
        if (s < 0) {
            return null;
        }
        String rest = system.substring(s + prefix.length());
        int j = rest.indexOf('\n');
        String id = (j > 0 ? rest.substring(0, j) : rest).trim();
        return id.isEmpty() ? null : id;
    }

    private record RequestPayload(String scene, String question, String sessionId,
                                  String userId, String courseId, String kbId, String kbIds, String note,
                                  String kbName, String kbScope) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("scene", scene);
            m.put("question", question);
            if (sessionId != null) {
                m.put("sessionId", sessionId);
            }
            putInt(m, "userId", userId);
            putInt(m, "courseId", courseId);
            putInt(m, "kbId", kbId);
            putIntList(m, "kbIds", kbIds);
            if (note != null && !note.isBlank()) {
                m.put("note", note);
            }
            if (kbName != null && !kbName.isBlank()) {
                m.put("kbName", kbName);
            }
            if (kbScope != null && !kbScope.isBlank()) {
                m.put("kbScope", kbScope);
            }
            return m;
        }

        private static void putInt(Map<String, Object> m, String key, String raw) {
            if (raw == null) {
                return;
            }
            try {
                m.put(key, Integer.parseInt(raw.trim()));
            } catch (NumberFormatException e) {
                log.warn("system 标记 {} 非数字，已忽略: {}", key, raw);
            }
        }

        private static void putIntList(Map<String, Object> m, String key, String raw) {
            if (raw == null) {
                return;
            }
            List<Integer> ids = new java.util.ArrayList<>();
            for (String part : raw.split(",")) {
                String s = part.trim();
                if (s.isEmpty()) {
                    continue;
                }
                try {
                    ids.add(Integer.parseInt(s));
                } catch (NumberFormatException e) {
                    log.warn("system 标记 {} 含非数字片段，已忽略: {}", key, s);
                }
            }
            if (!ids.isEmpty()) {
                m.put(key, ids);
            }
        }
    }
}