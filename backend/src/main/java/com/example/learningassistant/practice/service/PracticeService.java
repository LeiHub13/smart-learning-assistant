package com.example.learningassistant.practice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.learningassistant.ai.AIChatMessage;
import com.example.learningassistant.ai.ChatModel;
import com.example.learningassistant.ai.ChatModelFactory;
import com.example.learningassistant.common.BizException;
import com.example.learningassistant.practice.entity.Practice;
import com.example.learningassistant.practice.entity.PracticeQuestion;
import com.example.learningassistant.practice.entity.Question;
import com.example.learningassistant.practice.mapper.PracticeMapper;
import com.example.learningassistant.practice.mapper.PracticeQuestionMapper;
import com.example.learningassistant.practice.mapper.QuestionMapper;
import com.example.learningassistant.progress.entity.KnowledgeMastery;
import com.example.learningassistant.progress.mapper.KnowledgeMasteryMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 题库练习服务：抽题、客观题规则判分、主观题 LLM 批改、掌握度更新。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PracticeService {

    private final QuestionMapper questionMapper;
    private final PracticeMapper practiceMapper;
    private final PracticeQuestionMapper pqMapper;
    private final KnowledgeMasteryMapper masteryMapper;
    private final ChatModelFactory modelFactory;
    private final ObjectMapper objectMapper;

    private static final int FULL_SCORE_PER_QUESTION = 10;

    public List<Map<String, Object>> paper(Long courseId, int count) {
        if (count < 1) {
            count = 5;
        }
        List<Question> qs = questionMapper.selectList(new LambdaQueryWrapper<Question>()
                .eq(Question::getCourseId, courseId)
                .orderByAsc(Question::getId)
                .last("LIMIT " + Math.max(count, 200)));
        java.util.Collections.shuffle(qs);
        if (qs.size() > count) {
            qs = qs.subList(0, count);
        }
        return qs.stream().map(this::toPaperItem).toList();
    }

    public Practice submit(Long userId, Long courseId, List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) {
            throw new BizException("没有可提交的答案");
        }
        Practice p = new Practice();
        p.setUserId(userId);
        p.setCourseId(courseId);
        p.setTitle("练习 " + LocalDateTime.now().toString().substring(0, 16).replace('T', ' '));
        p.setTotalScore(FULL_SCORE_PER_QUESTION * items.size());
        p.setCreatedAt(LocalDateTime.now());
        practiceMapper.insert(p);

        int total = 0;
        Map<String, int[]> kpStats = new java.util.HashMap<>();
        for (Map<String, Object> it : items) {
            Long qid = Long.valueOf(String.valueOf(it.get("questionId")));
            String userAnswer = it.get("answer") == null ? "" : String.valueOf(it.get("answer"));
            Question q = questionMapper.selectById(qid);
            if (q == null) {
                continue;
            }
            Grade g = grade(q, userAnswer);

            PracticeQuestion pq = new PracticeQuestion();
            pq.setPracticeId(p.getId());
            pq.setQuestionId(qid);
            pq.setUserAnswer(userAnswer);
            pq.setScore(g.score());
            pq.setCorrect(g.score() >= 6);
            pq.setReview(g.review());
            pq.setKpName(q.getKpName());
            pqMapper.insert(pq);

            total += g.score();
            if (q.getKpName() != null && !q.getKpName().isBlank()) {
                int[] s = kpStats.computeIfAbsent(q.getKpName(), k -> new int[2]);
                s[0]++;
                if (pq.getCorrect()) {
                    s[1]++;
                }
            }
        }
        p.setScore(total);
        practiceMapper.updateById(p);
        updateMastery(userId, courseId, kpStats);
        return p;
    }

    private Grade grade(Question q, String userAnswer) {
        String type = q.getType();
        if ("问答".equals(type)) {
            return reviewByLlm(q.getStem(), q.getAnswer(), userAnswer);
        }
        boolean ok;
        if ("多选".equals(type)) {
            ok = normMulti(userAnswer).equals(normMulti(q.getAnswer()));
        } else {
            ok = userAnswer.trim().equalsIgnoreCase(q.getAnswer() == null ? "" : q.getAnswer().trim());
        }
        return new Grade(ok ? FULL_SCORE_PER_QUESTION : 0,
                ok ? "回答正确。" : "参考答案：" + q.getAnswer() + (q.getAnalysis() == null ? "" : "。" + q.getAnalysis()));
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
        ChatModel model = modelFactory.get();
        String raw = model.complete(List.of(
                new AIChatMessage("system",
                        "REVIEW_SUBJECTIVE\n你是批改老师，依据参考答案给学生答案打分(0-10分整数)，输出 JSON: {\"score\":数字,\"comment\":\"点评\"}"),
                new AIChatMessage("user", "【题目】" + stem + "\n【参考答案】" + (reference == null ? "" : reference)
                        + "\n【学生答案】" + userAnswer + "\n【结束】")));
        try {
            Map<String, Object> m = objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {
            });
            int score = ((Number) m.getOrDefault("score", 0)).intValue();
            String comment = String.valueOf(m.getOrDefault("comment", ""));
            return new Grade(score, comment);
        } catch (Exception e) {
            log.warn("LLM 批改解析失败，按 0 分处理: {}", raw);
            return new Grade(0, "批改解析失败，请人工复核");
        }
    }

    private void updateMastery(Long userId, Long courseId, Map<String, int[]> stats) {
        for (Map.Entry<String, int[]> e : stats.entrySet()) {
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
            if (km.getId() == null) {
                masteryMapper.insert(km);
            } else {
                masteryMapper.updateById(km);
            }
        }
    }

    public List<Practice> history(Long userId) {
        return practiceMapper.selectList(new LambdaQueryWrapper<Practice>()
                .eq(Practice::getUserId, userId)
                .orderByDesc(Practice::getCreatedAt));
    }

    public Map<String, Object> report(Long practiceId) {
        Practice p = practiceMapper.selectById(practiceId);
        if (p == null) {
            throw new BizException("练习不存在");
        }
        List<PracticeQuestion> pqs = pqMapper.selectList(new LambdaQueryWrapper<PracticeQuestion>()
                .eq(PracticeQuestion::getPracticeId, practiceId));
        List<Map<String, Object>> items = pqs.stream().map(pq -> {
            Question q = questionMapper.selectById(pq.getQuestionId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pq", pq);
            m.put("q", toPaperItem(q));
            return m;
        }).toList();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("id", p.getId());
        report.put("courseId", p.getCourseId());
        report.put("title", p.getTitle());
        report.put("totalScore", p.getTotalScore());
        report.put("score", p.getScore());
        report.put("createdAt", p.getCreatedAt());
        report.put("items", items);
        return report;
    }

    private Map<String, Object> toPaperItem(Question q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", q.getId());
        m.put("type", q.getType());
        m.put("stem", q.getStem());
        m.put("options", q.getOptions());
        m.put("answer", q.getAnswer());
        m.put("analysis", q.getAnalysis());
        m.put("kpName", q.getKpName());
        return m;
    }

    private record Grade(int score, String review) {
    }
}
