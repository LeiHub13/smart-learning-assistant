package com.example.learningassistant.chat.service;

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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private static final int HISTORY_ROUNDS = 5;
    private static final int RAG_TOP_K = 5;

    public List<ChatSession> sessions(Long userId) {
        return sessionMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChatSession>()
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

    public ChatSession updateSession(Long id, String title, Long courseId, Long kbId) {
        ChatSession s = requireSession(id);
        if (title != null && !title.isBlank()) {
            s.setTitle(title.trim());
        }
        s.setCourseId(courseId);
        s.setKbId(kbId);
        sessionMapper.updateById(s);
        return s;
    }

    public void deleteSession(Long id) {
        requireSession(id);
        messageMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, id));
        sessionMapper.deleteById(id);
    }

    public List<ChatMessage> messages(Long sessionId) {
        return messageMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .orderByAsc(ChatMessage::getCreatedAt));
    }

    /**
     * 流式对话：RAG 检索 + 历史拼装 -> 流式生成 -> 落库。
     */
    public void streamMessage(Long sessionId, String question,
                              Consumer<String> onDelta, Consumer<String> onDone) {
        ChatSession session = requireSession(sessionId);
        LocalDateTime now = LocalDateTime.now();

        ChatMessage userMsg = new ChatMessage();
        userMsg.setSessionId(sessionId);
        userMsg.setRole("user");
        userMsg.setContent(question);
        userMsg.setCreatedAt(now);
        messageMapper.insert(userMsg);

        List<AIChatMessage> history = historyMessages(sessionId, HISTORY_ROUNDS);
        final String sources;
        StringBuilder system = new StringBuilder();

        if (session.getKbId() != null) {
            String ctx = buildContext(session.getKbId(), question);
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
        system.append("\nSESSION_ID:").append(sessionId);

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
        List<ChatMessage> all = messages(sessionId);
        int size = all.size();
        if (size <= rounds) {
            return all.stream().map(m -> new AIChatMessage(m.getRole(), m.getContent())).toList();
        }
        return all.subList(size - rounds, size).stream()
                .map(m -> new AIChatMessage(m.getRole(), m.getContent())).toList();
    }

    private String buildContext(Long kbId, String question) {
        List<ScoredId> hits = vectorStore.search(embeddingService.embed(question), RAG_TOP_K);
        if (hits.isEmpty()) {
            return "暂无相关资料";
        }
        List<Long> chunkIds = hits.stream().map(ScoredId::id).toList();
        List<Chunk> chunks = chunkMapper.selectBatchIds(chunkIds);
        Map<Long, Chunk> byId = chunks.stream().collect(Collectors.toMap(Chunk::getId, c -> c));
        StringBuilder sb = new StringBuilder();
        int n = 1;
        for (Long cid : chunkIds) {
            Chunk c = byId.get(cid);
            if (c == null) {
                continue;
            }
            sb.append('[').append(n).append("] ").append(c.getContent()).append('\n');
            n++;
        }
        return sb.toString();
    }
}
