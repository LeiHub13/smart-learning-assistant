package com.example.learningassistant.practice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.learningassistant.ai.AIChatMessage;
import com.example.learningassistant.ai.ChatModel;
import com.example.learningassistant.ai.ChatModelFactory;
import com.example.learningassistant.practice.entity.Question;
import com.example.learningassistant.progress.entity.KnowledgeMastery;
import com.example.learningassistant.progress.mapper.KnowledgeMasteryMapper;
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

/**
 * 统一判分服务：客观题规则判分 + 主观题 LLM 批改 + 掌握度更新。
 * 题库练习与考试模块共用，保证评分口径一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GradingService {

    /** 每题满分（与组卷分值口径一致） */
    public static final int FULL_SCORE_PER_QUESTION = 10;

    private final ChatModelFactory modelFactory;
    private final ObjectMapper objectMapper;
    private final KnowledgeMasteryMapper masteryMapper;

    public record Grade(int score, String review, boolean correct) {
    }

    public Grade grade(Question q, String userAnswer) {
        String type = q.getType();
        if ("问答".equals(type)) {
            return reviewByLlm(q.getStem(), q.getAnswer(), userAnswer);
        }
        boolean ok;
        if ("多选".equals(type)) {
            ok = normMulti(userAnswer).equals(normMulti(q.getAnswer()));
        } else {
            ok = userAnswer != null && userAnswer.trim().equalsIgnoreCase(q.getAnswer() == null ? "" : q.getAnswer().trim());
        }
        return new Grade(ok ? FULL_SCORE_PER_QUESTION : 0,
                ok ? "回答正确。" : "参考答案：" + q.getAnswer() + (q.getAnalysis() == null ? "" : "。" + q.getAnalysis()),
                ok);
    }

    private String normMulti(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("[^a-zA-Z]", "").toUpperCase()
                .chars().sorted()
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
    }

    /** 评分点数量与文本长度上限：防止模型输出超长点评撑爆报告页。 */
    private static final int REVIEW_POINTS_MAX = 5;
    private static final int REVIEW_POINT_MAX = 100;
    private static final int REVIEW_NOTE_MAX = 200;
    private static final int REVIEW_SUMMARY_MAX = 300;

    /**
     * 主观题批改：把参考答案拆成评分点逐项判定（是否命中 + 一句依据），再给 0-10 总分与总评。
     * review 存结构化 JSON（summary + points），前端逐项渲染；模型没给评分点时退化为纯文本总评，
     * 与旧版 {score, comment} 输出保持兼容——老数据无需迁移，前端按 review 是否为 JSON 分流渲染。
     */
    private Grade reviewByLlm(String stem, String reference, String userAnswer) {
        try {
            ChatModel model = modelFactory.get();
            String raw = model.complete(List.of(
                    new AIChatMessage("system",
                            "REVIEW_SUBJECTIVE\n你是批改老师。把参考答案拆成 1-5 个评分点，逐点比对学生答案是否命中"
                                    + "（命中 true / 未命中 false），每个评分点给一句判定依据；再按命中情况给出 0-10 整数总分"
                                    + "与一句总评。只输出 JSON：{\"score\":数字,\"summary\":\"总评\","
                                    + "\"points\":[{\"point\":\"评分点\",\"hit\":true,\"note\":\"判定依据\"}]}"),
                    new AIChatMessage("user", "【题目】" + stem + "\n【参考答案】" + (reference == null ? "" : reference)
                            + "\n【学生答案】" + userAnswer + "\n【结束】")));
            Map<String, Object> m = objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {
            });
            int score = clampScore(m.get("score"));
            String summary = m.get("summary") == null ? ""
                    : clip(String.valueOf(m.get("summary")), REVIEW_SUMMARY_MAX);

            List<Map<String, Object>> points = normalizePoints(m.get("points"));
            if (!points.isEmpty()) {
                Map<String, Object> review = new LinkedHashMap<>();
                review.put("summary", summary);
                review.put("points", points);
                return new Grade(score, objectMapper.writeValueAsString(review), score >= 6);
            }
            // 模型没给出评分点：退化为纯文本总评（兼容旧版 {score, comment} 输出）
            String comment = m.get("comment") == null ? summary
                    : clip(String.valueOf(m.get("comment")), REVIEW_SUMMARY_MAX);
            return new Grade(score, comment, score >= 6);
        } catch (Exception e) {
            log.warn("LLM 主观批改解析失败，按 0 分处理: {}", e.getMessage());
            return new Grade(0, "批改解析失败，请人工复核", false);
        }
    }

    /** 评分点数组归一化：只收 1-5 条，point 非空才收，hit 宽松解析（true/是/命中），文本截断。 */
    private List<Map<String, Object>> normalizePoints(Object raw) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return result;
        }
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> p) || result.size() >= REVIEW_POINTS_MAX) {
                continue;
            }
            String point = p.get("point") == null ? "" : String.valueOf(p.get("point")).trim();
            if (point.isEmpty()) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("point", clip(point, REVIEW_POINT_MAX));
            item.put("hit", isHit(p.get("hit")));
            item.put("note", p.get("note") == null ? "" : clip(String.valueOf(p.get("note")), REVIEW_NOTE_MAX));
            result.add(item);
        }
        return result;
    }

    private static int clampScore(Object raw) {
        int s;
        try {
            s = raw instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(raw).trim());
        } catch (Exception e) {
            return 0;
        }
        return Math.max(0, Math.min(10, s));
    }

    private static boolean isHit(Object raw) {
        String v = raw == null ? "" : String.valueOf(raw).trim();
        return v.matches("(?i)(true|是|命中|√|y)");
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /**
     * 按知识点聚合更新掌握度（练习/考试共用）。
     * kpStats: kpName -> [做题数, 答对数]
     */
    public void updateMastery(Long userId, Long courseId, Map<String, int[]> kpStats) {
        for (Map.Entry<String, int[]> e : kpStats.entrySet()) {
            KnowledgeMastery km = masteryMapper.selectOne(new LambdaQueryWrapper<KnowledgeMastery>()
                    .eq(KnowledgeMastery::getUserId, userId)
                    .eq(KnowledgeMastery::getCourseId, courseId)
                    .eq(KnowledgeMastery::getKpName, e.getKey()));
            if (km == null) {
                km = new KnowledgeMastery();
                km.setUserId(userId);
                km.setCourseId(courseId);
                km.setKpName(e.getKey());
                km.setAttempts(0);
                km.setCorrectCount(0);
            }
            km.setAttempts(km.getAttempts() + e.getValue()[0]);
            km.setCorrectCount(km.getCorrectCount() + e.getValue()[1]);
            km.setMastery(km.getAttempts() == 0 ? 0.0 : km.getCorrectCount() * 100.0 / km.getAttempts());
            km.setLastPracticeAt(LocalDateTime.now());
            if (km.getId() == null) {
                masteryMapper.insert(km);
            } else {
                masteryMapper.updateById(km);
            }
        }
    }
}
