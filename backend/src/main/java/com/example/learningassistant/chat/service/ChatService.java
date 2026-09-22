package com.example.learningassistant.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.learningassistant.ai.AIChatMessage;
import com.example.learningassistant.ai.ChatModel;
import com.example.learningassistant.ai.ChatModelFactory;
import com.example.learningassistant.chat.entity.ChatMessage;
import com.example.learningassistant.chat.entity.ChatSession;
import com.example.learningassistant.chat.mapper.ChatMessageMapper;
import com.example.learningassistant.chat.mapper.ChatSessionMapper;
import com.example.learningassistant.common.BizException;
import com.example.learningassistant.progress.entity.KnowledgeMastery;
import com.example.learningassistant.progress.mapper.KnowledgeMasteryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 答疑服务：会话管理 + 答疑调用 + 消息落库。
 *
 * 链路（RAG 检索链路已整体迁移到 ai-service，见 ai-service/app/rag.py）：
 *   RAG 模式：system 带 RAG_QA + KB_ID 标记 -> ai-service 侧查询改写/向量召回/重排/组装引用
 *             -> 流式增量转发 -> done 事件回传 sources（引用编号，如 "1,2,3"）-> 落库
 *   自由模式：FREE 标记 + 会话记忆 -> 流式生成
 *   注：openai-compatible / spring-ai 适配器不经过 ai-service，无知识库检索能力，仅按普通对话回答。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final ChatModelFactory modelFactory;
    private final KnowledgeMasteryMapper masteryMapper;

    private static final int HISTORY_ROUNDS = 5;

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

        StringBuilder system = new StringBuilder();
        if (session.getKbId() != null) {
            system.append("RAG_QA\n你是智能学习助手，结合课程知识库资料回答学生问题，并标注引用编号[1][2]等。")
                    .append("\nKB_ID:").append(session.getKbId());
        } else {
            system.append("FREE\n你是智能学习助手，用中文友好地解答学生的学习问题。");
        }

        String profile = weakKpProfile(userId, session.getCourseId());
        if (!profile.isEmpty()) {
            // NOTE 供 ai-service 拼进系统提示（单行，标记以换行结尾）；其余适配器直接续在 system 后
            system.append("\nNOTE:").append(profile.replaceAll("\\s+", " "));
        }

        system.append("\nUSER_ID:").append(userId)
                .append("\nCOURSE_ID:").append(session.getCourseId() == null ? 0 : session.getCourseId())
                .append("\nSESSION_ID:").append(sessionId);

        List<AIChatMessage> messages = new ArrayList<>();
        messages.add(new AIChatMessage("system", system.toString()));
        messages.addAll(historyMessages(sessionId, HISTORY_ROUNDS));
        messages.add(new AIChatMessage("user", question));

        ChatModel model = modelFactory.get();
        StringBuilder acc = new StringBuilder();
        model.stream(messages, msg -> {
            acc.append(msg);
            onDelta.accept(msg);
        }, sources -> {
            String refs = sources == null ? "" : sources.trim();
            ChatMessage aiMsg = new ChatMessage();
            aiMsg.setSessionId(sessionId);
            aiMsg.setRole("assistant");
            aiMsg.setContent(acc.toString());
            aiMsg.setSources(refs.isEmpty() ? null : refs);
            aiMsg.setCreatedAt(LocalDateTime.now());
            messageMapper.insert(aiMsg);
            onDone.accept(refs);
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
