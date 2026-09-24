package com.example.learningassistant.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.learningassistant.agent.entity.AgentAction;
import com.example.learningassistant.agent.service.AgentActionService;
import com.example.learningassistant.common.ApiResponse;
import com.example.learningassistant.common.BizException;
import com.example.learningassistant.kb.entity.Chunk;
import com.example.learningassistant.kb.entity.Document;
import com.example.learningassistant.kb.entity.KnowledgeBase;
import com.example.learningassistant.kb.mapper.ChunkMapper;
import com.example.learningassistant.kb.mapper.DocumentMapper;
import com.example.learningassistant.kb.mapper.KnowledgeBaseMapper;
import com.example.learningassistant.notify.service.NotifyService;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private final NotifyService notifyService;
    private final PlanService planService;
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
     * 安排复习提醒：落一条定时站内通知，到点由 NotifyService 投递。
     * remindAt 支持 yyyy-MM-dd（当天 09:00）或 ISO 日期时间；解析失败或早于当前时间则立即投递。
     */
    @PostMapping("/actions/review")
    public ApiResponse<Map<String, Object>> scheduleReview(HttpServletRequest request,
                                                           @RequestBody Map<String, Object> body) {
        checkToken(request);
        Long userId = asLong(body.get("userId"));
        if (userId == null) {
            throw new BizException("缺少 userId");
        }
        String kp = clip(body.get("kpName"), 50);
        if (kp == null || kp.isBlank()) {
            throw new BizException("缺少知识点名称");
        }
        LocalDateTime at = parseRemindAt(body.get("remindAt"));
        String note = clip(body.get("note"), 100);
        String content = "「" + kp + "」的复习提醒"
                + (at == null ? "（现在）" : "，计划时间 " + at.format(FMT))
                + (note == null || note.isBlank() ? "" : "。备注：" + note);
        notifyService.send(userId, "review", "复习提醒：" + kp, content,
                at == null ? LocalDateTime.now() : at);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scheduled", at != null && at.isAfter(LocalDateTime.now()));
        data.put("remindAt", at == null ? null : at.format(FMT));
        data.put("content", content);
        return ApiResponse.ok(data);
    }

    /**
     * 学习计划打卡：幂等——已完成的任务不再反选，直接返回当前状态。
     * 任务不属于该用户时拒绝（tasksOf 只返回本人任务，越权 taskId 天然查不到）。
     */
    @PostMapping("/actions/plan-task-check")
    public ApiResponse<Map<String, Object>> checkInPlanTask(HttpServletRequest request,
                                                            @RequestBody Map<String, Object> body) {
        checkToken(request);
        Long userId = asLong(body.get("userId"));
        Long taskId = asLong(body.get("taskId"));
        if (userId == null || taskId == null) {
            throw new BizException("缺少 userId 或 taskId");
        }
        Map<String, Object> task = planService.tasksOf(userId, false).stream()
                .filter(t -> taskId.equals(asLong(t.get("taskId"))))
                .findFirst()
                .orElseThrow(() -> new BizException("任务不存在或不属于当前用户"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", taskId);
        data.put("title", task.get("title"));
        if (Boolean.TRUE.equals(task.get("done"))) {
            data.put("done", true);
            data.put("changed", false);
            data.put("message", "该任务此前已打卡，未重复操作");
            return ApiResponse.ok(data);
        }
        planService.checkIn(userId, taskId);
        data.put("done", true);
        data.put("changed", true);
        data.put("message", "打卡成功");
        return ApiResponse.ok(data);
    }

    /**
     * 登记待确认动作（add_material）：Agent 只能创建 proposal，真正入库发生在用户点击确认后。
     * 校验失败按 BizException 抛出（ApiResponse code!=0），由 Python 侧转成可读文案。
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

    private LocalDateTime parseRemindAt(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            return null;
        }
        String s = String.valueOf(raw).trim();
        try {
            if (s.length() <= 10) {
                return LocalDate.parse(s).atTime(9, 0);
            }
            return LocalDateTime.parse(s.replace(" ", "T"));
        } catch (Exception e) {
            return null;
        }
    }

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
