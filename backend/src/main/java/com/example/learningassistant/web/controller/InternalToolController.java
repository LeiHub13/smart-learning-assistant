package com.example.learningassistant.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.learningassistant.agent.entity.AgentAction;
import com.example.learningassistant.agent.service.AgentActionService;
import com.example.learningassistant.chat.entity.ChatMessage;
import com.example.learningassistant.chat.entity.ChatSession;
import com.example.learningassistant.chat.mapper.ChatMessageMapper;
import com.example.learningassistant.chat.mapper.ChatSessionMapper;
import com.example.learningassistant.common.ApiResponse;
import com.example.learningassistant.common.BizException;
import com.example.learningassistant.kb.entity.Chunk;
import com.example.learningassistant.kb.entity.Document;
import com.example.learningassistant.kb.entity.KnowledgeBase;
import com.example.learningassistant.favorite.service.FavoriteService;
import com.example.learningassistant.kb.mapper.ChunkMapper;
import com.example.learningassistant.kb.mapper.DocumentMapper;
import com.example.learningassistant.kb.mapper.KnowledgeBaseMapper;
import com.example.learningassistant.note.service.NoteService;
import com.example.learningassistant.plan.service.PlanService;
import com.example.learningassistant.practice.entity.Practice;
import com.example.learningassistant.practice.entity.PracticeQuestion;
import com.example.learningassistant.practice.entity.Question;
import com.example.learningassistant.practice.mapper.PracticeMapper;
import com.example.learningassistant.practice.mapper.PracticeQuestionMapper;
import com.example.learningassistant.practice.mapper.QuestionMapper;
import com.example.learningassistant.progress.entity.KnowledgeMastery;
import com.example.learningassistant.progress.mapper.KnowledgeMasteryMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内部工具 API：仅供 ai-service 的 Agent 工具回调取数，不暴露给前端。
 * 用 X-Internal-Token 头鉴权（与 ai-service 的 JAVA_TOOL_TOKEN 对应）。
 */
@RestController
@RequestMapping("/internal/tools")
@RequiredArgsConstructor
public class InternalToolController {

    private final KnowledgeMasteryMapper masteryMapper;
    private final PracticeMapper practiceMapper;
    private final PracticeQuestionMapper pqMapper;
    private final QuestionMapper questionMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final DocumentMapper documentMapper;
    private final ChunkMapper chunkMapper;
    private final ChatMessageMapper messageMapper;
    private final ChatSessionMapper sessionMapper;
    private final PlanService planService;
    private final NoteService noteService;
    private final FavoriteService favoriteService;
    private final AgentActionService agentActionService;

    @Value("${app.internal-tool-token:internal-tool-token}")
    private String internalToken;

    private void checkToken(HttpServletRequest request) {
        String token = request.getHeader("X-Internal-Token");
        if (!internalToken.equals(token)) {
            throw new BizException("内部接口鉴权失败");
        }
    }

    @GetMapping("/mastery")
    public ApiResponse<List<Map<String, Object>>> mastery(HttpServletRequest request,
                                                          @RequestParam Long userId,
                                                          @RequestParam(defaultValue = "1") Long courseId) {
        checkToken(request);
        List<KnowledgeMastery> list = masteryMapper.selectList(new LambdaQueryWrapper<KnowledgeMastery>()
                .eq(KnowledgeMastery::getUserId, userId)
                .eq(KnowledgeMastery::getCourseId, courseId)
                .orderByAsc(KnowledgeMastery::getMastery));
        List<Map<String, Object>> result = new ArrayList<>();
        for (KnowledgeMastery m : list) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kpName", m.getKpName());
            row.put("mastery", Math.round(m.getMastery()));
            row.put("attempts", m.getAttempts());
            row.put("correctCount", m.getCorrectCount());
            result.add(row);
        }
        return ApiResponse.ok(result);
    }

    @GetMapping("/wrong-book")
    public ApiResponse<List<Map<String, Object>>> wrongBook(HttpServletRequest request,
                                                            @RequestParam Long userId) {
        checkToken(request);
        List<Practice> practices = practiceMapper.selectList(new LambdaQueryWrapper<Practice>()
                .eq(Practice::getUserId, userId));
        if (practices.isEmpty()) {
            return ApiResponse.ok(List.of());
        }
        Map<Long, Practice> byId = new LinkedHashMap<>();
        for (Practice p : practices) {
            byId.put(p.getId(), p);
        }
        List<PracticeQuestion> wrongs = pqMapper.selectList(new LambdaQueryWrapper<PracticeQuestion>()
                .eq(PracticeQuestion::getCorrect, false)
                .orderByDesc(PracticeQuestion::getId)
                .last("LIMIT 20"));
        List<Map<String, Object>> result = new ArrayList<>();
        for (PracticeQuestion pq : wrongs) {
            Practice p = byId.get(pq.getPracticeId());
            if (p == null) {
                continue;
            }
            Question q = questionMapper.selectById(pq.getQuestionId());
            if (q == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stem", q.getStem());
            row.put("kpName", q.getKpName());
            row.put("userAnswer", pq.getUserAnswer());
            row.put("answer", q.getAnswer());
            row.put("wrongAt", p.getCreatedAt() == null ? null : p.getCreatedAt().toString());
            result.add(row);
        }
        return ApiResponse.ok(result);
    }

    @GetMapping("/recent-practices")
    public ApiResponse<List<Map<String, Object>>> recentPractices(HttpServletRequest request,
                                                                  @RequestParam Long userId) {
        checkToken(request);
        List<Practice> list = practiceMapper.selectList(new LambdaQueryWrapper<Practice>()
                .eq(Practice::getUserId, userId)
                .orderByDesc(Practice::getCreatedAt)
                .last("LIMIT 10"));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Practice p : list) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("title", p.getTitle());
            row.put("score", p.getScore());
            row.put("totalScore", p.getTotalScore());
            row.put("createdAt", p.getCreatedAt() == null ? null : p.getCreatedAt().toString());
            result.add(row);
        }
        return ApiResponse.ok(result);
    }

    @GetMapping("/kb-documents")
    public ApiResponse<List<Map<String, Object>>> kbDocuments(HttpServletRequest request,
                                                              @RequestParam(defaultValue = "1") Long courseId) {
        checkToken(request);
        List<KnowledgeBase> kbs = kbMapper.selectList(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getCourseId, courseId));
        List<Map<String, Object>> result = new ArrayList<>();
        for (KnowledgeBase kb : kbs) {
            List<Document> docs = documentMapper.selectList(new LambdaQueryWrapper<Document>()
                    .eq(Document::getKbId, kb.getId()));
            for (Document d : docs) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("kbName", kb.getName());
                row.put("fileName", d.getFileName());
                row.put("chunkCount", d.getChunkCount());
                result.add(row);
            }
        }
        return ApiResponse.ok(result);
    }

    /** 学习笔记清单（Agent 读工具）：courseId 可选过滤，正文截断供引用。 */
    @GetMapping("/notes")
    public ApiResponse<List<Map<String, Object>>> notes(HttpServletRequest request,
                                                        @RequestParam Long userId,
                                                        @RequestParam(required = false) Long courseId) {
        checkToken(request);
        List<Map<String, Object>> result = new ArrayList<>();
        for (var n : noteService.list(userId, courseId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("noteId", n.getId());
            row.put("courseId", n.getCourseId());
            row.put("kpName", n.getKpName());
            row.put("title", n.getTitle());
            row.put("content", clip(n.getContent(), 300));
            row.put("updatedAt", n.getUpdatedAt() == null ? null : n.getUpdatedAt().toString());
            result.add(row);
        }
        return ApiResponse.ok(result);
    }

    /** 收藏夹概览（Agent 读工具）：题目概览不含答案，courseId 可选过滤。 */
    @GetMapping("/favorites")
    public ApiResponse<List<Map<String, Object>>> favorites(HttpServletRequest request,
                                                            @RequestParam Long userId,
                                                            @RequestParam(required = false) Long courseId) {
        checkToken(request);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> f : favoriteService.list(userId, courseId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("questionId", asLong(f.get("questionId")));
            row.put("courseName", f.get("courseName"));
            row.put("type", f.get("type"));
            row.put("stem", clip(f.get("stem"), 150));
            row.put("kpName", f.get("kpName"));
            row.put("difficulty", f.get("difficulty"));
            result.add(row);
        }
        return ApiResponse.ok(result);
    }

    /** 课程题库检索（Agent 读工具）：按关键词匹配题干/知识点，返回题目概览与 questionId。 */
    @GetMapping("/questions")
    public ApiResponse<List<Map<String, Object>>> questions(HttpServletRequest request,
                                                            @RequestParam Long courseId,
                                                            @RequestParam(required = false) String keyword,
                                                            @RequestParam(defaultValue = "10") Integer limit) {
        checkToken(request);
        int size = Math.min(Math.max(limit == null ? 10 : limit, 1), 20);
        String kw = keyword == null || keyword.isBlank() ? null : keyword.trim();
        List<Question> list = questionMapper.selectList(new LambdaQueryWrapper<Question>()
                .eq(Question::getCourseId, courseId)
                .and(kw != null, w -> w.like(Question::getStem, kw).or().like(Question::getKpName, kw))
                .orderByDesc(Question::getId)
                .last("LIMIT " + size));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Question q : list) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("questionId", q.getId());
            row.put("type", q.getType());
            row.put("stem", clip(q.getStem(), 200));
            row.put("kpName", q.getKpName());
            row.put("difficulty", q.getDifficulty());
            row.put("source", q.getSource());
            result.add(row);
        }
        return ApiResponse.ok(result);
    }

    /**
     * 过往会话定向检索（Agent 读工具）：按关键词在该学生其他答疑会话的提问里匹配，
     * 返回命中问题与紧随其后的回答片段；keyword 留空返回最近的提问。
     * excludeSessionId 排除当前会话——其内容已在会话记忆里，无需重复检索。
     */
    @GetMapping("/past-qa")
    public ApiResponse<List<Map<String, Object>>> pastQa(HttpServletRequest request,
                                                         @RequestParam Long userId,
                                                         @RequestParam(required = false) String keyword,
                                                         @RequestParam(required = false) String excludeSessionId,
                                                         @RequestParam(defaultValue = "5") Integer limit) {
        checkToken(request);
        int size = Math.min(Math.max(limit == null ? 5 : limit, 1), 10);
        Long excludeParsed = null;
        try {
            excludeParsed = excludeSessionId == null || excludeSessionId.isBlank()
                    ? null : Long.valueOf(excludeSessionId.trim());
        } catch (NumberFormatException ignored) {
            // 非数字的会话标识直接忽略
        }
        final Long exclude = excludeParsed;
        List<ChatSession> sessions = sessionMapper.selectList(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getUserId, userId));
        List<Long> sessionIds = sessions.stream()
                .filter(s -> !s.getId().equals(exclude))
                .map(ChatSession::getId)
                .toList();
        if (sessionIds.isEmpty()) {
            return ApiResponse.ok(List.of());
        }
        Map<Long, String> titles = new java.util.HashMap<>();
        for (ChatSession s : sessions) {
            titles.put(s.getId(), s.getTitle() == null ? "" : s.getTitle());
        }
        String kw = keyword == null || keyword.isBlank() ? null : keyword.trim();
        List<ChatMessage> hits = messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .in(ChatMessage::getSessionId, sessionIds)
                .eq(ChatMessage::getRole, "user")
                .like(kw != null, ChatMessage::getContent, kw)
                .orderByDesc(ChatMessage::getId)
                .last("LIMIT " + size));
        List<Map<String, Object>> result = new ArrayList<>();
        for (ChatMessage hit : hits) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sessionId", hit.getSessionId());
            row.put("sessionTitle", titles.getOrDefault(hit.getSessionId(), ""));
            row.put("askedAt", hit.getCreatedAt() == null ? null : hit.getCreatedAt().toString());
            row.put("question", clip(hit.getContent(), 200));
            ChatMessage answer = messageMapper.selectOne(new LambdaQueryWrapper<ChatMessage>()
                    .eq(ChatMessage::getSessionId, hit.getSessionId())
                    .eq(ChatMessage::getRole, "assistant")
                    .gt(ChatMessage::getId, hit.getId())
                    .orderByAsc(ChatMessage::getId)
                    .last("LIMIT 1"));
            row.put("answer", answer == null ? null : clip(answer.getContent(), 400));
            result.add(row);
        }
        return ApiResponse.ok(result);
    }

    /**
     * chunk 正文下发：ai-service 建向量索引时按游标分页拉取（RAG 检索链路已迁移到 Python，
     * t_chunk 仍是正文的唯一数据源，向量库只是它的派生索引）。
     *
     * @param cursor 上一页最后一条 chunkId，首页传 0
     */
    @GetMapping("/chunks")
    public ApiResponse<List<Map<String, Object>>> chunks(HttpServletRequest request,
                                                         @RequestParam(required = false) Long kbId,
                                                         @RequestParam(required = false) Long docId,
                                                         @RequestParam(defaultValue = "0") Long cursor,
                                                         @RequestParam(defaultValue = "500") Integer limit) {
        checkToken(request);
        int size = Math.min(Math.max(limit == null ? 500 : limit, 1), 2000);
        List<Chunk> list = chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .gt(Chunk::getId, cursor)
                .eq(kbId != null, Chunk::getKbId, kbId)
                .eq(docId != null, Chunk::getDocId, docId)
                .orderByAsc(Chunk::getId)
                .last("LIMIT " + size));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Chunk c : list) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("chunkId", c.getId());
            row.put("docId", c.getDocId());
            row.put("kbId", c.getKbId());
            row.put("content", c.getContent());
            result.add(row);
        }
        return ApiResponse.ok(result);
    }

    // ===== Agent 写动作（X-Internal-Token 保护；文案由服务端模板生成，不接受模型自由文本）=====

    /**
     * 学习任务清单：Agent 先查 taskId，再决定打卡哪一个。
     */
    @GetMapping("/plan-tasks")
    public ApiResponse<List<Map<String, Object>>> planTasks(HttpServletRequest request,
                                                            @RequestParam Long userId,
                                                            @RequestParam(defaultValue = "true") boolean onlyPending) {
        checkToken(request);
        return ApiResponse.ok(planService.tasksOf(userId, onlyPending));
    }

    /**
     * 登记 Agent 待确认动作：Agent 侧所有写动作的唯一入口（add_material / schedule_review /
     * finish_plan_task / add_note / favorite_question / generate_questions / open_page），
     * 只落 proposal，真正写入（或页面跳转）发生在用户点「确认执行」之后。
     */
    @PostMapping("/actions/propose")
    public ApiResponse<Map<String, Object>> proposeAction(HttpServletRequest request,
                                                          @RequestBody Map<String, Object> body) {
        checkToken(request);
        Long userId = asLong(body.get("userId"));
        if (userId == null) {
            throw new BizException("缺少 userId");
        }
        String kind = clip(body.get("kind"), 40);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = body.get("payload") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        AgentAction action = agentActionService.propose(userId, asLong(body.get("courseId")),
                asLong(body.get("sessionId")), kind, payload);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("actionId", action.getId());
        data.put("summary", action.getSummary());
        data.put("kind", action.getKind());
        data.put("expiresAt", action.getExpiresAt() == null ? null : action.getExpiresAt().format(FMT));
        return ApiResponse.ok(data);
    }

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private String clip(Object raw, int max) {
        if (raw == null) {
            return null;
        }
        String s = String.valueOf(raw).replaceAll("[\\r\\n]+", " ").trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private Long asLong(Object raw) {
        return raw instanceof Number n ? n.longValue()
                : raw == null || String.valueOf(raw).isBlank() ? null : Long.valueOf(String.valueOf(raw).trim());
    }
}
