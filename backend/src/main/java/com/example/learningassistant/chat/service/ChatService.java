package com.example.learningassistant.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.learningassistant.ai.AIChatMessage;
import com.example.learningassistant.ai.ChatModel;
import com.example.learningassistant.ai.ChatModelFactory;
import com.example.learningassistant.ai.EmbeddingService;
import com.example.learningassistant.chat.entity.ChatMessage;
import com.example.learningassistant.chat.entity.ChatSession;
import com.example.learningassistant.chat.mapper.ChatMessageMapper;
import com.example.learningassistant.chat.mapper.ChatSessionMapper;
import com.example.learningassistant.common.BizException;
import com.example.learningassistant.infra.vector.VectorStore;
import com.example.learningassistant.infra.vector.VectorStore.ScoredId;
import com.example.learningassistant.kb.entity.Chunk;
import com.example.learningassistant.kb.mapper.ChunkMapper;
import com.example.learningassistant.progress.entity.KnowledgeMastery;
import com.example.learningassistant.progress.mapper.KnowledgeMasteryMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 答疑服务：会话管理 + RAG 检索 + 流式生成 + 消息落库。
 *
 * 链路（架构文档 §7.1）：
 *   RAG 模式：向量检索知识库 -> 组装带 [n] 引用的上下文 -> ChatModel.stream() 增量转发
 *   自由模式：系统提示 + 最近 N 轮历史 -> ChatModel.stream()
 *   完成回调：保存 assistant 消息（含 sources）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final ChatModelFactory modelFactory;
    private final VectorStore vectorStore;
    private final EmbeddingService embeddingService;
    private final ChunkMapper chunkMapper;
    private final KnowledgeMasteryMapper masteryMapper;
    private final ObjectMapper objectMapper;

    private static final int HISTORY_ROUNDS = 5;
    private static final int RAG_TOP_K = 5;
    private static final int RAG_RERANK_CANDIDATES = 20;

    @Value("${app.rag.rewrite-enabled:true}")
    private boolean rewriteEnabled;

    @Value("${app.rag.rerank-enabled:true}")
    private boolean rerankEnabled;

    public List<ChatSession> sessions(Long userId) {
        return sessionMapper.selectList(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getUserId, userId)
                .orderByDesc(ChatSession::getCreatedAt));
    }

    public ChatSession createSession(Long userId, Long courseId, Long kbId) {
        ChatSession s = new ChatSession();
        s.setUserId(userId);
        s.setCourseId(courseId);
        s.setKbId(kbId);
        s.setTitle("新对话");
        s.setCreatedAt(LocalDateTime.now());
        sessionMapper.insert(s);
        return s;
    }

    public ChatSession requireSession(Long id) {
        ChatSession s = sessionMapper.selectById(id);
        if (s == null) {
            throw new BizException("会话不存在");
        }
        return s;
    }

    /** 归属校验：会话不存在或不属于当前用户一律拒绝（防越权读写他人会话）。 */
    public ChatSession requireOwnedSession(Long id, Long userId) {
        ChatSession s = requireSession(id);
        if (userId == null || !userId.equals(s.getUserId())) {
            throw new BizException("会话不存在或无权访问");
        }
        return s;
    }

    public ChatSession updateSession(Long id, Long userId, String title, Long courseId, Long kbId) {
        ChatSession s = requireOwnedSession(id, userId);
        if (title != null && !title.isBlank()) {
            s.setTitle(title.trim());
        }
        // 只覆盖显式传入的字段，避免重命名时误清知识库绑定
        if (courseId != null) {
            s.setCourseId(courseId);
        }
        if (kbId != null) {
            s.setKbId(kbId);
        }
        sessionMapper.updateById(s);
        return s;
    }

    public void deleteSession(Long id, Long userId) {
        requireOwnedSession(id, userId);
        messageMapper.delete(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, id));
        sessionMapper.deleteById(id);
    }

    public List<ChatMessage> messages(Long sessionId, Long userId) {
        requireOwnedSession(sessionId, userId);
        return messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .orderByAsc(ChatMessage::getCreatedAt));
    }

    /**
     * 流式对话：可选 query 改写 -> RAG 检索（可选 Rerank） -> 历史拼装 -> 流式生成 -> 落库。
     */
    public void streamMessage(Long sessionId, String question, Long userId,
                              Consumer<String> onDelta, Consumer<String> onDone) {
        ChatSession session = requireOwnedSession(sessionId, userId);
        LocalDateTime now = LocalDateTime.now();

        // 首次对话自动命名：标题仍为默认"新对话"时，取第一条提问截断为会话名（用户手动改过的不覆盖）
        if ("新对话".equals(session.getTitle()) && question != null && !question.isBlank()) {
            String derived = question.replaceAll("\\s+", " ").trim();
            if (derived.length() > 20) {
                derived = derived.substring(0, 20) + "…";
            }
            session.setTitle(derived);
            sessionMapper.updateById(session);
        }

        ChatMessage userMsg = new ChatMessage();
        userMsg.setSessionId(sessionId);
        userMsg.setRole("user");
        userMsg.setContent(question);
        userMsg.setCreatedAt(now);
        messageMapper.insert(userMsg);

        List<AIChatMessage> history = historyMessages(sessionId, HISTORY_ROUNDS);
        String effectiveQuestion = question;
        if (rewriteEnabled && !history.isEmpty()) {
            effectiveQuestion = rewriteQuery(question, history);
        }

        final String sources;
        StringBuilder system = new StringBuilder();

        if (session.getKbId() != null) {
            String ctx = buildContext(session.getKbId(), effectiveQuestion);
            if (ctx.contains("暂无相关资料")) {
                sources = "";
                system.append("RAG_QA\n你是智能学习助手，用中文友好地解答问题。\n【知识库资料】").append(ctx).append("【资料结束】");
            } else {
                sources = ctx.lines().filter(l -> l.startsWith("["))
                        .map(l -> l.substring(1, l.indexOf(']')))
                        .collect(Collectors.joining(","));
                system.append("RAG_QA\n你是智能学习助手，结合下方课程知识库资料回答学生问题，并标注引用编号[1][2]等。\n【知识库资料】").append(ctx).append("【资料结束】");
            }
        } else {
            sources = "";
            system.append("FREE\n你是智能学习助手，用中文友好地解答学生的学习问题。");
        }

        String profile = weakKpProfile(userId, session.getCourseId());
        if (!profile.isEmpty()) {
            system.append("\n").append(profile);
        }

        system.append("\nUSER_ID:").append(userId)
                .append("\nCOURSE_ID:").append(session.getCourseId() == null ? 0 : session.getCourseId())
                .append("\nSESSION_ID:").append(sessionId);

        List<AIChatMessage> messages = new ArrayList<>();
        messages.add(new AIChatMessage("system", system.toString()));
        messages.addAll(history);
        messages.add(new AIChatMessage("user", question));

        ChatModel model = modelFactory.get();
        StringBuilder acc = new StringBuilder();
        model.stream(messages, delta -> {
            acc.append(delta);
            onDelta.accept(delta);
        }, () -> {
            ChatMessage aiMsg = new ChatMessage();
            aiMsg.setSessionId(sessionId);
            aiMsg.setRole("assistant");
            aiMsg.setContent(acc.toString());
            aiMsg.setSources(sources.isEmpty() ? null : sources);
            aiMsg.setCreatedAt(LocalDateTime.now());
            messageMapper.insert(aiMsg);
            onDone.accept(sources);
        }, e -> {
            log.error("对话流式生成失败: sessionId={}", sessionId, e);
            throw new BizException("生成失败，请重试");
        });
    }

    private List<AIChatMessage> historyMessages(Long sessionId, int rounds) {
        List<ChatMessage> all = messageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByAsc(ChatMessage::getCreatedAt));
        int size = all.size();
        if (size <= rounds) {
            return all.stream().map(m -> new AIChatMessage(m.getRole(), m.getContent())).toList();
        }
        return all.subList(size - rounds, size).stream()
                .map(m -> new AIChatMessage(m.getRole(), m.getContent())).toList();
    }

    private String buildContext(Long kbId, String question) {
        int topN = rerankEnabled ? RAG_RERANK_CANDIDATES : RAG_TOP_K;
        List<ScoredId> hits = vectorStore.search(embeddingService.embed(question), topN);
        if (hits.isEmpty()) {
            return "暂无相关资料";
        }
        List<Long> chunkIds = hits.stream().map(ScoredId::id).toList();
        List<Chunk> chunks = chunkMapper.selectBatchIds(chunkIds);
        Map<Long, Chunk> byId = chunks.stream().collect(Collectors.toMap(Chunk::getId, c -> c));

        List<Chunk> ordered = new ArrayList<>();
        for (Long cid : chunkIds) {
            Chunk c = byId.get(cid);
            if (c != null) {
                ordered.add(c);
            }
        }
        if (rerankEnabled && ordered.size() > RAG_TOP_K) {
            ordered = rerankChunks(question, ordered);
        }

        StringBuilder sb = new StringBuilder();
        int n = 1;
        for (Chunk c : ordered.subList(0, Math.min(ordered.size(), RAG_TOP_K))) {
            sb.append('[').append(n).append("] ").append(c.getContent()).append('\n');
            n++;
        }
        return sb.toString();
    }

    private String rewriteQuery(String question, List<AIChatMessage> history) {
        try {
            StringBuilder sb = new StringBuilder();
            for (AIChatMessage m : history) {
                sb.append(m.role()).append(":").append(m.content()).append("\n");
            }
            sb.append("user:").append(question);
            ChatModel model = modelFactory.get();
            String rewritten = model.complete(List.of(
                    new AIChatMessage("system", "REWRITE\n你是查询改写器。把用户最新问题改写成独立完整的检索查询。只输出改写后的查询本身。"),
                    new AIChatMessage("user", sb.toString())));
            if (rewritten != null && !rewritten.isBlank()) {
                return rewritten.trim();
            }
        } catch (Exception e) {
            log.warn("Query 改写失败，使用原问题: {}", e.getMessage());
        }
        return question;
    }

    private List<Chunk> rerankChunks(String question, List<Chunk> candidates) {
        try {
            StringBuilder prompt = new StringBuilder();
            prompt.append("问题：").append(question).append("\n\n");
            for (int i = 0; i < candidates.size(); i++) {
                prompt.append("[").append(i + 1).append("] ")
                        .append(candidates.get(i).getContent()).append("\n");
            }
            ChatModel model = modelFactory.get();
            String raw = model.complete(List.of(
                    new AIChatMessage("system", "RERANK\n你是相关性排序器。按与问题的相关程度从高到低排序，只输出 JSON 数组（元素为片段编号整数）。"),
                    new AIChatMessage("user", prompt.toString())));
            List<Integer> indices = objectMapper.readValue(raw, new TypeReference<List<Integer>>() {
            });
            List<Chunk> result = new ArrayList<>();
            for (Integer idx : indices) {
                if (idx != null && idx >= 1 && idx <= candidates.size()) {
                    result.add(candidates.get(idx - 1));
                    if (result.size() >= RAG_TOP_K) {
                        break;
                    }
                }
            }
            if (!result.isEmpty()) {
                return result;
            }
        } catch (Exception e) {
            log.warn("Rerank 失败，使用向量原排序: {}", e.getMessage());
        }
        return candidates;
    }

    private String weakKpProfile(Long userId, Long courseId) {
        if (userId == null || courseId == null) {
            return "";
        }
        List<KnowledgeMastery> list = masteryMapper.selectList(new LambdaQueryWrapper<KnowledgeMastery>()
                .eq(KnowledgeMastery::getUserId, userId)
                .eq(KnowledgeMastery::getCourseId, courseId)
                .lt(KnowledgeMastery::getMastery, 50.0)
                .orderByAsc(KnowledgeMastery::getMastery)
                .last("LIMIT 5"));
        if (list.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【该学生薄弱知识点（掌握度<50%）】");
        for (KnowledgeMastery m : list) {
            sb.append(m.getKpName()).append("(").append(Math.round(m.getMastery())).append("%); ");
        }
        return sb.toString();
    }
}
