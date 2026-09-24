package com.example.learningassistant.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.learningassistant.agent.entity.AgentAction;
import com.example.learningassistant.agent.mapper.AgentActionMapper;
import com.example.learningassistant.common.BizException;
import com.example.learningassistant.kb.entity.Document;
import com.example.learningassistant.kb.entity.KnowledgeBase;
import com.example.learningassistant.kb.service.KbService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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

    /** 动作类型白名单：本期仅 add_material，无删除/修改类动作。 */
    private static final Set<String> ALLOWED_KINDS = Set.of("add_material");
    private static final int TITLE_MAX = 100;
    private static final int CONTENT_MAX = 20000;
    private static final int SUMMARY_MAX = 300;
    private static final int RESULT_MAX = 300;
    private static final int FILE_NAME_MAX = 200;
    private static final long TTL_MINUTES = 30;

    private final AgentActionMapper actionMapper;
    private final KbService kbService;
    private final ObjectMapper objectMapper;

    /**
     * Agent 登记一个待确认动作（不产生任何业务写入）。
     */
    public AgentAction propose(Long userId, Long courseId, Long sessionId,
                               String kind, Map<String, Object> payload) {
        if (userId == null) {
            throw new BizException("缺少 userId");
        }
        if (kind == null || !ALLOWED_KINDS.contains(kind)) {
            throw new BizException("不支持的动作类型");
        }
        Map<String, Object> src = payload == null ? Map.of() : payload;
        if ("add_material".equals(kind)) {
            return proposeAddMaterial(userId, courseId, sessionId, src);
        }
        throw new BizException("不支持的动作类型");
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

        AgentAction a = new AgentAction();
        a.setUserId(userId);
        a.setCourseId(courseId);
        a.setSessionId(sessionId);
        a.setKind("add_material");
        a.setPayload(writeJson(stored));
        a.setSummary(clip("存资料进知识库：《" + title + "》→ " + kb.getName(), SUMMARY_MAX));
        a.setStatus("pending");
        a.setCreatedAt(LocalDateTime.now());
        a.setExpiresAt(LocalDateTime.now().plusMinutes(TTL_MINUTES));
        actionMapper.insert(a);
        log.info("用户 {} 登记待确认动作 {}（kind={}，kb={}）", userId, a.getId(), a.getKind(), kb.getId());
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
            return resultOf(a, null);
        }
        if (!"pending".equals(a.getStatus())) {
            throw new BizException("该动作已失效");
        }
        if (a.getExpiresAt() != null && a.getExpiresAt().isBefore(LocalDateTime.now())) {
            a.setStatus("expired");
            actionMapper.updateById(a);
            throw new BizException("确认已过期，请重新发起");
        }

        Long docId = null;
        try {
            if ("add_material".equals(a.getKind())) {
                docId = executeAddMaterial(a, payload);
            } else {
                throw new BizException("不支持的动作类型");
            }
        } catch (Exception e) {
            log.warn("待确认动作 {} 执行失败: {}", actionId, e.getMessage());
            a.setStatus("failed");
            a.setResult(clip("执行失败：" + e.getMessage(), RESULT_MAX));
            actionMapper.updateById(a);
            throw new BizException("执行失败，请稍后重试");
        }
        return resultOf(a, docId);
    }

    private Long executeAddMaterial(AgentAction a, Map<String, Object> payload) {
        Long kbId = asLong(payload.get("kbId"));
        String title = String.valueOf(payload.getOrDefault("title", ""));
        // 文件名防注入：剥掉路径分隔符与换行，仅保留服务端拼装的固定前缀
        String safeTitle = title.replaceAll("[/\\\\\\r\\n]", "").trim();
        String fileName = clip("Agent收藏-" + safeTitle + ".md", FILE_NAME_MAX);
        Document doc = kbService.indexTextDocument(kbId, fileName,
                String.valueOf(payload.getOrDefault("content", "")));
        a.setStatus("executed");
        a.setResult(clip("已存入《" + payload.getOrDefault("kbName", "") + "》知识库（文档 id="
                + doc.getId() + "）", RESULT_MAX));
        actionMapper.updateById(a);
        log.info("待确认动作 {} 已执行，文档 {} 入库", a.getId(), doc.getId());
        return doc.getId();
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

    private Map<String, Object> resultOf(AgentAction a, Long docId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("actionId", a.getId());
        data.put("kind", a.getKind());
        data.put("status", a.getStatus());
        data.put("result", a.getResult());
        data.put("docId", docId);
        data.put("kbId", asLong(readPayload(a).get("kbId")));
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
