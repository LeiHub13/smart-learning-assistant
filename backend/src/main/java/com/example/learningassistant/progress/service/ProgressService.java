package com.example.learningassistant.progress.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.learningassistant.ai.AIChatMessage;
import com.example.learningassistant.ai.ChatModel;
import com.example.learningassistant.ai.ChatModelFactory;
import com.example.learningassistant.infra.cache.CacheService;
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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 学情分析服务：掌握度聚合、错题本、AI 复习建议。
 * AI 建议不在 summary 里自动生成——只回读缓存（有则带出），由用户在页面点「开始分析」
 * 显式触发（regenerateAdvice，Redis/内存缓存 7 天）。
 * summary 聚合（掌握度 + 错题本）整体缓存：错题本聚合是全量扫描+逐题回表，代价高；
 * 练习/考试提交时按 key 失效（PracticeService/ExamService），重新生成建议时也失效。
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
    private final CacheService cacheService;
    private final ObjectMapper objectMapper;

    private static final Duration ADVICE_TTL = Duration.ofDays(7);
    private static final Duration SUMMARY_TTL = Duration.ofMinutes(10);

    private String adviceKey(Long userId, Long courseId) {
        return "progress:advice:" + userId + ":" + courseId;
    }

    /** summary 聚合缓存 key（提交侧失效共用，公开静态避免各处拼字符串） */
    public static String summaryKey(Long userId, Long courseId) {
        return "progress:summary:" + userId + ":" + courseId;
    }

    public Map<String, Object> summary(Long userId, Long courseId) {
        // 聚合结果整体缓存：命中直接返回（JSON 反序列化，响应结构与实时计算一致）
        String cached = cacheService.get(summaryKey(userId, courseId));
        if (cached != null && !cached.isBlank()) {
            try {
                return objectMapper.readValue(cached, new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                log.warn("学情 summary 缓存反序列化失败，回退实时计算: {}", e.getMessage());
            }
        }

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
        // 建议只回读缓存，绝不在 summary 内调 LLM——分析由用户点「开始分析」显式触发
        String advice = cacheService.get(adviceKey(userId, courseId));
        if (advice != null && !advice.isBlank()) {
            summary.put("advice", advice);
            summary.put("adviceCached", true);
        } else {
            summary.put("advice", null);
            summary.put("adviceCached", false);
        }
        writeSummaryCache(userId, courseId, summary);
        return summary;
    }

    private void writeSummaryCache(Long userId, Long courseId, Map<String, Object> summary) {
        try {
            cacheService.set(summaryKey(userId, courseId), objectMapper.writeValueAsString(summary), SUMMARY_TTL);
        } catch (Exception e) {
            log.warn("学情 summary 缓存写入失败: {}", e.getMessage());
        }
    }

    /**
     * 显式重新生成：无视缓存强制调 LLM，结果写回缓存，并失效 summary 聚合缓存
     * （否则旧 summary 里冻结的旧建议会继续返回最长 SUMMARY_TTL）。
     */
    public Map<String, Object> regenerateAdvice(Long userId, Long courseId) {
        List<KnowledgeMastery> masteries = masteryMapper.selectList(new LambdaQueryWrapper<KnowledgeMastery>()
                .eq(KnowledgeMastery::getUserId, userId)
                .eq(KnowledgeMastery::getCourseId, courseId)
                .orderByDesc(KnowledgeMastery::getMastery));
        String advice = generateAdvice(masteries);
        if (!masteries.isEmpty()) {
            cacheService.set(adviceKey(userId, courseId), advice, ADVICE_TTL);
        }
        cacheService.delete(summaryKey(userId, courseId));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("advice", advice);
        result.put("adviceCached", false);
        return result;
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
