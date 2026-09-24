package com.example.learningassistant.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.learningassistant.agent.entity.AgentAction;
import com.example.learningassistant.agent.mapper.AgentActionMapper;
import com.example.learningassistant.common.BizException;
import com.example.learningassistant.kb.entity.Document;
import com.example.learningassistant.kb.entity.KnowledgeBase;
import com.example.learningassistant.kb.service.KbService;
import com.example.learningassistant.notify.service.NotifyService;
import com.example.learningassistant.plan.service.PlanService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Agent 待确认动作服务：写操作先由 Agent 登记 proposal（pending），
 * 真正的数据库写入只发生在用户点击「确认执行」时（confirm）。
 *
 * 安全底线：目标知识库、摘要文案、文件名全部由服务端解析/生成，
 * 模型侧传入的任何 ID/文本一律不直接信任（跨课程 kbId 会被拒绝）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentActionService {

    /** 动作类型白名单：全部是「先登记、用户确认后才生效」的写动作。 */
    private static final Set<String> ALLOWED_KINDS =
            Set.of("add_material", "schedule_review", "finish_plan_task");
    private static final int TITLE_MAX = 100;
    private static final int CONTENT_MAX = 20000;
    private static final int SUMMARY_MAX = 300;
    private static final int RESULT_MAX = 300;
    private static final int FILE_NAME_MAX = 200;
    private static final int KP_MAX = 50;
    private static final int NOTE_MAX = 100;
    private static final long TTL_MINUTES = 30;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AgentActionMapper actionMapper;
    private final KbService kbService;
    private final NotifyService notifyService;
    private final PlanService planService;
    private final ObjectMapper objectMapper;

    /**
     * Agent 登记一个待确认动作（不产生任何业务写入）。
     */
    public AgentAction propose(Long userId, Long courseId, Long sessionId,
                               String kind, Map<String, Object> payload) {
        if (userId == null) {
            throw new BizException("缺少 userId");
        }
        Map<String, Object> src = payload == null ? Map.of() : payload;
        // Set.of(...).contains(null) 会抛 NPE，kind 为空要先挡掉
        if (kind == null || !ALLOWED_KINDS.contains(kind)) {
            throw new BizException("不支持的动作类型");
        }
        return switch (kind) {
            case "add_material" -> proposeAddMaterial(userId, courseId, sessionId, src);
            case "schedule_review" -> proposeReview(userId, sessionId, src);
            case "finish_plan_task" -> proposeCheckIn(userId, sessionId, src);
            default -> throw new BizException("不支持的动作类型");
        };
    }

    private AgentAction proposeAddMaterial(Long userId, Long courseId, Long sessionId,
                                           Map<String, Object> src) {
        String title = src.get("title") == null ? "" : String.valueOf(src.get("title")).trim();
        String content = src.get("content") == null ? "" : String.valueOf(src.get("content")).trim();
        if (title.isEmpty()) {
            throw new BizException("缺少资料标题");
        }
        if (content.isEmpty()) {
            throw new BizException("缺少资料内容");
        }
        if (title.length() > TITLE_MAX) {
            title = title.substring(0, TITLE_MAX);
        }
        if (content.length() > CONTENT_MAX) {
            content = content.substring(0, CONTENT_MAX);
        }

        // 目标知识库由服务端解析：payload 带 kbId 时必须核验它确实属于本课程，否则一律按课程最近知识库落点
        KnowledgeBase kb;
        if (src.get("kbId") != null) {
            kb = kbService.requireKb(asLong(src.get("kbId")));
            if (!Objects.equals(kb.getCourseId(), courseId)) {
                throw new BizException("知识库不属于本课程");
            }
        } else {
            List<KnowledgeBase> kbs = courseId == null ? List.of() : kbService.kbList(courseId);
            if (kbs.isEmpty()) {
                throw new BizException("该课程还没有知识库，请先在知识库页面创建");
            }
            kb = kbs.get(0);
        }

        // 归一化存储：确认执行只读这里的 kbId/kbName/title/content，不再回看模型侧数据
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("kbId", kb.getId());
        stored.put("kbName", kb.getName());
        stored.put("title", title);
        stored.put("content", content);

        AgentAction a = newAction(userId, courseId, sessionId, "add_material", stored,
                "存资料进知识库：《" + title + "》→ " + kb.getName());
        log.info("用户 {} 登记待确认动作 {}（kind={}，kb={}）", userId, a.getId(), a.getKind(), kb.getId());
        return a;
    }

    private AgentAction proposeReview(Long userId, Long sessionId, Map<String, Object> src) {
        String kp = clip(src.get("kpName"), KP_MAX);
        if (kp == null || kp.isBlank()) {
            throw new BizException("缺少知识点名称");
        }
        String note = clip(src.get("note"), NOTE_MAX);
        // 提醒时间在登记阶段就归一化落库，确认执行不再解析模型给的时间串；
        // 已经过去的时间按「立即」处理（与摘要文案保持一致，不会出现"安排到了昨天"）
        LocalDateTime at = parseRemindAt(src.get("remindAt"));
        if (at != null && at.isBefore(LocalDateTime.now())) {
            at = null;
        }

        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("kpName", kp);
        stored.put("remindAt", at == null ? null : at.toString());
        stored.put("note", note == null || note.isBlank() ? null : note);

        AgentAction a = newAction(userId, null, sessionId, "schedule_review", stored,
                "安排复习提醒：「" + kp + "」"
                        + (at == null ? "（确认后立刻提醒）" : "，时间 " + FMT.format(at)));
        log.info("用户 {} 登记待确认动作 {}（kind=schedule_review）", userId, a.getId());
        return a;
    }

    private AgentAction proposeCheckIn(Long userId, Long sessionId, Map<String, Object> src) {
        Long taskId = asLong(src.get("taskId"));
        if (taskId == null) {
            throw new BizException("缺少任务 id");
        }
        // 登记阶段就校验归属与状态：tasksOf 只返回本人任务，越权 taskId 天然查不到
        Map<String, Object> task = planService.tasksOf(userId, false).stream()
                .filter(t -> taskId.equals(asLong(t.get("taskId"))))
                .findFirst()
                .orElseThrow(() -> new BizException("任务不存在或不属于当前用户"));
        if (Boolean.TRUE.equals(task.get("done"))) {
            throw new BizException("该任务此前已打卡，无需重复操作");
        }
        String title = clip(task.get("title"), TITLE_MAX);

        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("taskId", taskId);
        stored.put("title", title);

        AgentAction a = newAction(userId, null, sessionId, "finish_plan_task", stored,
                "学习任务打卡：" + (title == null || title.isBlank() ? "任务 #" + taskId : "《" + title + "》"));
        log.info("用户 {} 登记待确认动作 {}（kind=finish_plan_task，task={}）", userId, a.getId(), taskId);
        return a;
    }

    /** 三种动作共用：pending + 30 分钟有效期，摘要由服务端模板生成。 */
    private AgentAction newAction(Long userId, Long courseId, Long sessionId, String kind,
                                  Map<String, Object> storedPayload, String summary) {
        AgentAction a = new AgentAction();
        a.setUserId(userId);
        a.setCourseId(courseId);
        a.setSessionId(sessionId);
        a.setKind(kind);
        a.setPayload(writeJson(storedPayload));
        a.setSummary(clip(summary, SUMMARY_MAX));
        a.setStatus("pending");
        a.setCreatedAt(LocalDateTime.now());
        a.setExpiresAt(LocalDateTime.now().plusMinutes(TTL_MINUTES));
        actionMapper.insert(a);
        return a;
    }

    /**
     * 该用户（可选限会话）的待确认动作；顺带懒清理已超时的行（无定时任务）。
     */
    public List<AgentAction> pendingFor(Long userId, Long sessionId) {
        if (userId == null) {
            return List.of();
        }
        List<AgentAction> rows = actionMapper.selectList(new LambdaQueryWrapper<AgentAction>()
                .eq(AgentAction::getUserId, userId)
                .eq(AgentAction::getStatus, "pending")
                .orderByAsc(AgentAction::getId));
        LocalDateTime now = LocalDateTime.now();
        List<AgentAction> result = new ArrayList<>();
        for (AgentAction a : rows) {
            if (a.getExpiresAt() != null && a.getExpiresAt().isBefore(now)) {
                a.setStatus("expired");
                actionMapper.updateById(a);
                continue;
            }
            if (sessionId == null || sessionId.equals(a.getSessionId())) {
                result.add(a);
            }
        }
        return result;
    }

    /**
     * 用户确认执行：归属校验 + 幂等（executed 直接回旧结果，绝不重复写库）。
     */
    public Map<String, Object> confirm(Long userId, Long actionId) {
        AgentAction a = actionMapper.selectById(actionId);
        if (a == null) {
            throw new BizException("动作不存在");
        }
        if (userId == null || !userId.equals(a.getUserId())) {
            throw new BizException("无权操作该动作");
        }
        Map<String, Object> payload = readPayload(a);

        if ("executed".equals(a.getStatus())) {
            return resultOf(a);
        }
        if (!"pending".equals(a.getStatus())) {
            throw new BizException("该动作已失效");
        }
        if (a.getExpiresAt() != null && a.getExpiresAt().isBefore(LocalDateTime.now())) {
            a.setStatus("expired");
            actionMapper.updateById(a);
            throw new BizException("确认已过期，请重新发起");
        }

        try {
            switch (a.getKind()) {
                case "add_material" -> executeAddMaterial(a, payload);
                case "schedule_review" -> executeReview(a, payload);
                case "finish_plan_task" -> executeCheckIn(a, payload);
                default -> throw new BizException("不支持的动作类型");
            }
        } catch (Exception e) {
            log.warn("待确认动作 {} 执行失败: {}", actionId, e.getMessage());
            a.setStatus("failed");
            a.setResult(clip("执行失败：" + e.getMessage(), RESULT_MAX));
            actionMapper.updateById(a);
            throw new BizException("执行失败，请稍后重试");
        }
        return resultOf(a);
    }

    private void executeAddMaterial(AgentAction a, Map<String, Object> payload) {
        Long kbId = asLong(payload.get("kbId"));
        String title = String.valueOf(payload.getOrDefault("title", ""));
        // 文件名防注入：剥掉路径分隔符与换行，仅保留服务端拼装的固定前缀
        String safeTitle = title.replaceAll("[/\\\\\\r\\n]", "").trim();
        String fileName = clip("Agent收藏-" + safeTitle + ".md", FILE_NAME_MAX);
        Document doc = kbService.indexTextDocument(kbId, fileName,
                String.valueOf(payload.getOrDefault("content", "")));
        // docId 回写进 payload，重复确认时还能报出同一篇文档
        payload.put("docId", doc.getId());
        a.setPayload(writeJson(payload));
        a.setStatus("executed");
        a.setResult(clip("已存入《" + payload.getOrDefault("kbName", "") + "》知识库（文档 id="
                + doc.getId() + "）", RESULT_MAX));
        actionMapper.updateById(a);
        log.info("待确认动作 {} 已执行，文档 {} 入库", a.getId(), doc.getId());
    }

    private void executeReview(AgentAction a, Map<String, Object> payload) {
        String kp = String.valueOf(payload.getOrDefault("kpName", ""));
        String note = payload.get("note") == null ? null : String.valueOf(payload.get("note"));
        LocalDateTime at = parseRemindAt(payload.get("remindAt"));
        String content = "「" + kp + "」的复习提醒"
                + (at == null ? "（现在）" : "，计划时间 " + FMT.format(at))
                + (note == null || note.isBlank() ? "" : "。备注：" + note);
        notifyService.send(a.getUserId(), "review", "复习提醒：" + kp, content,
                at == null ? LocalDateTime.now() : at);
        a.setStatus("executed");
        a.setResult(clip(at == null ? "复习提醒已送达：" + kp : "复习提醒已安排：" + FMT.format(at), RESULT_MAX));
        actionMapper.updateById(a);
        log.info("待确认动作 {} 已执行，复习提醒 kp={}", a.getId(), kp);
    }

    private void executeCheckIn(AgentAction a, Map<String, Object> payload) {
        Long taskId = asLong(payload.get("taskId"));
        if (taskId == null) {
            throw new BizException("动作数据缺少 taskId");
        }
        // 登记到确认之间任务可能已被别处打卡；checkIn 自身幂等，这里如实回报
        boolean already = planService.tasksOf(a.getUserId(), false).stream()
                .filter(t -> taskId.equals(asLong(t.get("taskId"))))
                .findFirst()
                .map(t -> Boolean.TRUE.equals(t.get("done")))
                .orElse(false);
        if (!already) {
            planService.checkIn(a.getUserId(), taskId);
        }
        a.setStatus("executed");
        a.setResult(clip(already ? "该任务此前已打卡，未重复操作"
                : "已打卡：" + payload.getOrDefault("title", "任务 #" + taskId), RESULT_MAX));
        actionMapper.updateById(a);
        log.info("待确认动作 {} 已执行，任务 {} 打卡（此前已完成={}）", a.getId(), taskId, already);
    }

    /** 提醒时间：yyyy-MM-dd 视为当天 09:00，ISO 日期时间原样解析，解析不了视为「立即」。 */
    private static LocalDateTime parseRemindAt(Object raw) {
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

    /**
     * 用户取消待确认动作：仅 pending 可取消。
     */
    public void cancel(Long userId, Long actionId) {
        AgentAction a = actionMapper.selectById(actionId);
        if (a == null) {
            throw new BizException("动作不存在");
        }
        if (userId == null || !userId.equals(a.getUserId())) {
            throw new BizException("无权操作该动作");
        }
        if (!"pending".equals(a.getStatus())) {
            throw new BizException("仅待确认动作可取消");
        }
        a.setStatus("cancelled");
        actionMapper.updateById(a);
    }

    /** 确认卡片对外视图（SSE done 事件与 GET 列表共用，形状稳定且小）。 */
    public static Map<String, Object> toView(AgentAction a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("kind", a.getKind());
        m.put("summary", a.getSummary());
        m.put("expiresAt", a.getExpiresAt() == null ? null : a.getExpiresAt().toString());
        return m;
    }

    private Map<String, Object> resultOf(AgentAction a) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("actionId", a.getId());
        data.put("kind", a.getKind());
        data.put("status", a.getStatus());
        data.put("result", a.getResult());
        Map<String, Object> payload = readPayload(a);
        data.put("kbId", asLong(payload.get("kbId")));
        data.put("docId", asLong(payload.get("docId")));
        return data;
    }

    private Map<String, Object> readPayload(AgentAction a) {
        try {
            return objectMapper.readValue(a.getPayload(), new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("动作 {} payload 解析失败: {}", a.getId(), e.getMessage());
            throw new BizException("动作数据损坏，请重新发起");
        }
    }

    private String writeJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new BizException("动作登记失败，请重试");
        }
    }

    /** 模型侧文本：压掉换行与首尾空白后再按上限截断，避免把换行注进摘要/正文。 */
    private static String clip(Object raw, int max) {
        return raw == null ? null
                : clip(String.valueOf(raw).replaceAll("[\\r\\n]+", " ").trim(), max);
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static Long asLong(Object raw) {
        if (raw instanceof Number n) {
            return n.longValue();
        }
        if (raw == null || String.valueOf(raw).isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new BizException("参数格式错误：kbId");
        }
    }
}
