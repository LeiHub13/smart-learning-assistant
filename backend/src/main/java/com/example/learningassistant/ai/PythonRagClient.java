package com.example.learningassistant.ai;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ai-service 的 RAG 客户端：文档解析、分块、向量化与语义检索统一在 Python 侧完成，
 * Java 只负责把 Python 返回的 chunk 正文持久化（t_chunk 仍是唯一数据源）并消费检索结果。
 *
 * 契约见 ai-service/app/main.py 的 /ai/retrieve、/ai/index、/ai/index/delete、/ai/rag/stats、
 * /ai/parse、/ai/chunk；鉴权与 Agent 工具回调共用同一把内部 token（X-Internal-Token）。
 */
@Slf4j
@Component
public class PythonRagClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String internalToken;

    public PythonRagClient(RestTemplate restTemplate,
                           @Value("${app.model.python-base-url:http://localhost:8000}") String baseUrl,
                           @Value("${app.internal-tool-token:internal-tool-token}") String internalToken) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.internalToken = internalToken;
    }

    /** 一条向量召回结果，score 越大越相关。 */
    public record Hit(long chunkId, Long docId, Long kbId, String content, double score) {
    }

    /** ai-service RAG 端点不可用（服务未启动 / 索引失败）。 */
    @Getter
    public static class RagException extends RuntimeException {
        public RagException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 向量召回；kbId 为空表示全库检索。
     *
     * @throws RagException ai-service 不可用时抛出，调用方决定降级策略
     */
    public List<Hit> retrieve(String query, Long kbId, int topK) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("kbId", kbId);
        body.put("topK", topK);
        return toHits(post("/ai/retrieve", body).get("hits"));
    }

    /**
     * 向量召回并限定在给定知识库集合内：一个课程可有多个知识库，按课程隔离检索必须传多个 kbId，
     * 否则会命中其他课程的资料。
     *
     * @param kbIds 允许命中的知识库 id；为空等价于全库检索
     * @throws RagException ai-service 不可用时抛出，调用方决定降级策略
     */
    public List<Hit> retrieveIn(String query, List<Long> kbIds, int topK) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("kbIds", kbIds);
        body.put("topK", topK);
        return toHits(post("/ai/retrieve", body).get("hits"));
    }

    private static List<Hit> toHits(Object raw) {
        List<Hit> hits = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> h && h.get("chunkId") instanceof Number id) {
                    hits.add(new Hit(id.longValue(), asLong(h.get("docId")), asLong(h.get("kbId")),
                            String.valueOf(h.get("content")), asDouble(h.get("score"))));
                }
            }
        }
        return hits;
    }

    /** 索引指定文档 / 整个知识库；两者均为空表示全部 chunk。 */
    public int index(Long kbId, Long docId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kbId", kbId);
        body.put("docId", docId);
        return asInt(post("/ai/index", body).get("indexed"), 0);
    }

    /**
     * 解析上传的原始文件字节并切成 chunks（解析与分块均在 ai-service 侧完成）。
     *
     * @throws RagException 解析失败，message 为 ai-service 返回的可读原因
     */
    public List<String> parseChunks(String fileName, String contentType, byte[] data) {
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(data) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("X-Internal-Token", internalToken);
        try {
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    baseUrl + "/ai/parse", HttpMethod.POST, new HttpEntity<>(form, headers), MAP_TYPE);
            return asChunks(resp.getBody() == null ? null : resp.getBody().get("chunks"));
        } catch (RestClientResponseException e) {
            throw new RagException(readableDetail(e), e);
        } catch (Exception e) {
            throw new RagException("调用 ai-service /ai/parse 失败: " + e.getMessage(), e);
        }
    }

    /** 纯文本切块（前端粘贴文本、AI 讲义、种子数据等非文件场景）。 */
    public List<String> chunkText(String content) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", content);
        return asChunks(post("/ai/chunk", body).get("chunks"));
    }

    /** 全量重建：Python 侧清空向量集合后从 t_chunk 重新拉取。 */
    public int rebuild() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("all", true);
        return asInt(post("/ai/index", body).get("indexed"), 0);
    }

    /** 删除向量：按文档或按 chunkId 列表。 */
    public void delete(Long docId, List<Long> chunkIds) {
        if (docId == null && (chunkIds == null || chunkIds.isEmpty())) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("docId", docId);
        body.put("chunkIds", chunkIds);
        post("/ai/index/delete", body);
    }

    /** 向量库观测信息（embedding provider / 向量条数 / 集合名）。 */
    public Map<String, Object> stats() {
        try {
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    baseUrl + "/ai/rag/stats", HttpMethod.GET, new HttpEntity<>(headers()), MAP_TYPE);
            Map<String, Object> body = resp.getBody();
            return body == null ? Map.of() : body;
        } catch (Exception e) {
            throw new RagException("获取向量库状态失败: " + e.getMessage(), e);
        }
    }

    /** 向量条数；ai-service 不可达时返回 -1，调用方据此跳过一致性比对。 */
    public long vectorCount() {
        try {
            Long v = asLong(stats().get("vectors"));
            return v == null ? -1 : v;
        } catch (Exception e) {
            log.warn("读取向量库计数失败: {}", e.getMessage());
            return -1;
        }
    }

    private Map<?, ?> post(String path, Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = restTemplate.postForObject(baseUrl + path,
                    new HttpEntity<>(body, headers()), Map.class);
            if (resp == null) {
                throw new RagException("ai-service " + path + " 返回空响应", null);
            }
            return resp;
        } catch (RagException e) {
            throw e;
        } catch (RestClientResponseException e) {
            // 4xx 带 body 时优先透出 ai-service 给的可读原因（如解析失败提示），而非 HTTP 状态串
            throw new RagException(readableDetail(e), e);
        } catch (Exception e) {
            throw new RagException("调用 ai-service " + path + " 失败: " + e.getMessage(), e);
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static String readableDetail(RestClientResponseException e) {
        try {
            Map<?, ?> body = OBJECT_MAPPER.readValue(e.getResponseBodyAsString(), Map.class);
            Object detail = body.get("detail");
            if (detail != null && !String.valueOf(detail).isBlank()) {
                return String.valueOf(detail);
            }
        } catch (Exception ignored) {
            // body 不是 JSON，退回技术信息
        }
        return "调用 ai-service 失败: " + e.getMessage();
    }

    private static List<String> asChunks(Object raw) {
        List<String> chunks = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    chunks.add(String.valueOf(item));
                }
            }
        }
        return chunks;
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", internalToken);
        return headers;
    }

    private static Long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }

    private static double asDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static int asInt(Object v, int fallback) {
        return v instanceof Number n ? n.intValue() : fallback;
    }
}
