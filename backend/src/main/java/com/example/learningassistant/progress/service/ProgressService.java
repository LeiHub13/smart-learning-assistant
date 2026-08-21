package com.example.learningassistant.progress.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.learningassistant.ai.AIChatMessage;
import com.example.learningassistant.ai.ChatModel;
import com.example.learningassistant.ai.ChatModelFactory;
import com.example.learningassistant.practice.entity.Practice;
import com.example.learningassistant.practice.entity.PracticeQuestion;
import com.example.learningassistant.practice.entity.Question;
import com.example.learningassistant.practice.mapper.PracticeMapper;
import com.example.learningassistant.practice.mapper.PracticeQuestionMapper;
import com.example.learningassistant.practice.mapper.QuestionMapper;
import com.example.learningassistant.progress.entity.KnowledgeMastery;
import com.example.learningassistant.progress.mapper.KnowledgeMasteryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 学情分析服务：掌握度聚合、错题本、AI 复习建议。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProgressService {

    private final KnowledgeMasteryMapper masteryMapper;
    private final PracticeMapper practiceMapper;
    private final PracticeQuestionMapper pqMapper;
    private final QuestionMapper questionMapper;
    private final ChatModelFactory modelFactory;

    public Map<String, Object> summary(Long userId, Long courseId) {
        List<KnowledgeMastery> masteries = masteryMapper.selectList(new LambdaQueryWrapper<KnowledgeMastery>()
                .eq(KnowledgeMastery::getUserId, userId)
                .eq(KnowledgeMastery::getCourseId, courseId)
                .orderByDesc(KnowledgeMastery::getMastery));

        double average = masteries.stream().mapToDouble(KnowledgeMastery::getMastery).average().orElse(0);
        int totalAttempts = masteries.stream().mapToInt(KnowledgeMastery::getAttempts).sum();

        List<Map<String, Object>> wrongBook = wrongBook(userId, courseId);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("masteries", masteries);
        summary.put("averageMastery", Math.round(average));
        summary.put("totalAttempts", totalAttempts);
        summary.put("wrongBook", wrongBook);
        summary.put("advice", generateAdvice(masteries));
        return summary;
    }

    private List<Map<String, Object>> wrongBook(Long userId, Long courseId) {
        List<Practice> practices = practiceMapper.selectList(new LambdaQueryWrapper<Practice>()
                .eq(Practice::getUserId, userId)
                .eq(Practice::getCourseId, courseId));
        if (practices.isEmpty()) {
            return List.of();
        }
        Map<Long, LocalDateTime> practiceTime = new LinkedHashMap<>();
        for (Practice p : practices) {
            practiceTime.put(p.getId(), p.getCreatedAt());
        }
        List<PracticeQuestion> wrongs = pqMapper.selectList(new LambdaQueryWrapper<PracticeQuestion>()
                .eq(PracticeQuestion::getCorrect, false)
                .orderByDesc(PracticeQuestion::getId));
        List<Map<String, Object>> result = new ArrayList<>();
        for (PracticeQuestion pq : wrongs) {
            LocalDateTime t = practiceTime.get(pq.getPracticeId());
            if (t == null) {
                continue;
            }
            Question q = questionMapper.selectById(pq.getQuestionId());
            if (q == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stem", q.getStem());
            m.put("kpName", q.getKpName());
            m.put("userAnswer", pq.getUserAnswer());
            m.put("answer", q.getAnswer());
            m.put("wrongAt", t);
            result.add(m);
        }
        return result;
    }

    public String generateAdvice(List<KnowledgeMastery> masteries) {
        try {
            ChatModel model = modelFactory.get();
            StringBuilder data = new StringBuilder();
            for (KnowledgeMastery m : masteries) {
                data.append(m.getKpName()).append(":").append(Math.round(m.getMastery())).append("%(")
                        .append(m.getCorrectCount()).append('/').append(m.getAttempts()).append("); ");
            }
            return model.complete(List.of(
                    new AIChatMessage("system", "ADVICE\n你是学习规划师，根据掌握度数据给出个性化复习建议。"),
                    new AIChatMessage("user", "【掌握度】" + data + "【结束】\n请给出复习建议。")));
        } catch (Exception e) {
            log.warn("AI 建议生成失败，返回默认文案", e);
            return "坚持\"学-练-复盘\"循环，重点复习掌握度低于 60% 的知识点。";
        }
    }
}
