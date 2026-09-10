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

    private Grade reviewByLlm(String stem, String reference, String userAnswer) {
        try {
            ChatModel model = modelFactory.get();
            String raw = model.complete(List.of(
                    new AIChatMessage("system",
                            "REVIEW_SUBJECTIVE\n你是批改老师，依据参考答案给学生答案打分(0-10分整数)，输出 JSON: {\"score\":数字,\"comment\":\"点评\"}"),
                    new AIChatMessage("user", "【题目】" + stem + "\n【参考答案】" + (reference == null ? "" : reference)
                            + "\n【学生答案】" + userAnswer + "\n【结束】")));
            Map<String, Object> m = objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {
            });
            int score = ((Number) m.getOrDefault("score", 0)).intValue();
            String comment = String.valueOf(m.getOrDefault("comment", ""));
            return new Grade(score, comment, score >= 6);
        } catch (Exception e) {
            log.warn("LLM 批改解析失败，按 0 分处理: {}", e.getMessage());
            return new Grade(0, "批改解析失败，请人工复核", false);
        }
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
