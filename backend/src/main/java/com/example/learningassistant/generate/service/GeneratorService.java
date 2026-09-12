package com.example.learningassistant.generate.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.learningassistant.ai.AIChatMessage;
import com.example.learningassistant.ai.ChatModel;
import com.example.learningassistant.ai.ChatModelFactory;
import com.example.learningassistant.generate.entity.GeneratedContent;
import com.example.learningassistant.generate.mapper.GeneratedContentMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.learningassistant.practice.entity.Question;
import com.example.learningassistant.practice.mapper.QuestionMapper;
import com.example.learningassistant.progress.entity.KnowledgeMastery;
import com.example.learningassistant.progress.mapper.KnowledgeMasteryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * AI 内容生成服务：讲义 / 练习题，生成结果入库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GeneratorService {

    private final ChatModelFactory modelFactory;
    private final GeneratedContentMapper contentMapper;
    private final QuestionMapper questionMapper;
    private final KnowledgeMasteryMapper masteryMapper;
    private final ObjectMapper objectMapper;

    /**
     * 生成讲义（Markdown）并入库。
     */
    public GeneratedContent generateLecture(Long userId, Long courseId, String topic, String kp) {
        ChatModel model = modelFactory.get();
        String md = model.complete(List.of(
                new AIChatMessage("system", "GEN_LECTURE\n你是课程教师助手，请为课程生成一份结构清晰的 Markdown 讲义。"),
                new AIChatMessage("user", "主题:" + topic + "\n知识点:" + (kp == null || kp.isBlank() ? "核心概念" : kp) + "\n请生成讲义。")));

        GeneratedContent g = new GeneratedContent();
        g.setUserId(userId);
        g.setCourseId(courseId);
        g.setType("lecture");
        g.setTitle(topic);
        g.setContent(md);
        g.setCreatedAt(LocalDateTime.now());
        contentMapper.insert(g);
        return g;
    }

    /**
     * 生成练习题：根据用户掌握度自适应难度 -> LLM 出题 -> JSON 解析入库（source=AI）。
     */
    public List<Question> generateQuestions(Long userId, Long courseId, String kp, int count) {
        String difficulty = determineDifficulty(userId, courseId, kp);
        ChatModel model = modelFactory.get();
        String raw = model.complete(List.of(
                new AIChatMessage("system",
                        "GEN_QUESTIONS\n你是出题老师，只输出 JSON 数组，字段: type(单选/多选/判断/问答), stem, options(数组[{k,v}]), answer, analysis, kpName, difficulty。难度要求：" + difficulty),
                new AIChatMessage("user", "知识点:" + (kp == null || kp.isBlank() ? "核心概念" : kp)
                        + "\n数量:" + count + "\n难度:" + difficulty + "\n请生成练习题。")));
        List<Question> saved = new java.util.ArrayList<>();
        try {
            List<Map<String, Object>> list = objectMapper.readValue(raw, new TypeReference<List<Map<String, Object>>>() {
            });
            for (Map<String, Object> m : list) {
                Question q = new Question();
                q.setCourseId(courseId);
                q.setType(String.valueOf(m.getOrDefault("type", "单选")));
                q.setStem(String.valueOf(m.getOrDefault("stem", "")));
                Object opts = m.get("options");
                q.setOptions(opts == null ? null : objectMapper.writeValueAsString(opts));
                q.setAnswer(m.get("answer") == null ? null : String.valueOf(m.get("answer")));
                q.setAnalysis(m.get("analysis") == null ? null : String.valueOf(m.get("analysis")));
                q.setKpName(String.valueOf(m.getOrDefault("kpName", kp)));
                q.setDifficulty(String.valueOf(m.getOrDefault("difficulty", difficulty)));
                q.setSource("AI");
                q.setCreatedAt(LocalDateTime.now());
                questionMapper.insert(q);
                saved.add(q);
            }
        } catch (Exception e) {
            log.warn("AI 出题解析失败，题目未入库: {}", raw);
            throw new com.example.learningassistant.common.BizException("AI 出题解析失败，请重试");
        }

        GeneratedContent g = new GeneratedContent();
        g.setUserId(userId);
        g.setCourseId(courseId);
        g.setType("questions");
        g.setTitle("AI 生成练习（" + saved.size() + " 题）");
        g.setContent(raw);
        g.setCreatedAt(LocalDateTime.now());
        contentMapper.insert(g);
        return saved;
    }

    public List<GeneratedContent> history(Long userId) {
        return contentMapper.selectList(new LambdaQueryWrapper<GeneratedContent>()
                .eq(GeneratedContent::getUserId, userId)
                .orderByDesc(GeneratedContent::getCreatedAt));
    }

    /** 生成内容详情（归属校验，供下载等用途）。 */
    public GeneratedContent contentDetail(Long userId, Long id) {
        GeneratedContent g = contentMapper.selectById(id);
        if (g == null || userId == null || !g.getUserId().equals(userId)) {
            throw new com.example.learningassistant.common.BizException("内容不存在或无权访问");
        }
        return g;
    }

    /**
     * 流式生成讲义：逐字返回并保存。
     */
    public void streamLecture(Long userId, Long courseId, String topic, String kp,
                              java.util.function.Consumer<String> onDelta, java.util.function.Consumer<GeneratedContent> onDone, java.util.function.Consumer<Throwable> onError) {
        ChatModel model = modelFactory.get();
        StringBuilder acc = new StringBuilder();
        model.stream(List.of(
                        new AIChatMessage("system", "GEN_LECTURE\n你是课程教师助手，请为课程生成一份结构清晰的 Markdown 讲义。"),
                        new AIChatMessage("user", "主题:" + topic + "\n知识点:" + (kp == null || kp.isBlank() ? "核心概念" : kp) + "\n请生成讲义。")),
                delta -> {
                    acc.append(delta);
                    onDelta.accept(delta);
                },
                () -> {
                    GeneratedContent g = new GeneratedContent();
                    g.setUserId(userId);
                    g.setCourseId(courseId);
                    g.setType("lecture");
                    g.setTitle(topic);
                    g.setContent(acc.toString());
                    g.setCreatedAt(LocalDateTime.now());
                    contentMapper.insert(g);
                    onDone.accept(g);
                },
                onError);
    }

    private String determineDifficulty(Long userId, Long courseId, String kp) {
        if (userId == null || courseId == null || kp == null || kp.isBlank()) {
            return "进阶";
        }
        KnowledgeMastery m = masteryMapper.selectOne(new LambdaQueryWrapper<KnowledgeMastery>()
                .eq(KnowledgeMastery::getUserId, userId)
                .eq(KnowledgeMastery::getCourseId, courseId)
                .eq(KnowledgeMastery::getKpName, kp));
        if (m == null) {
            return "进阶";
        }
        double mastery = m.getMastery() == null ? 0.0 : m.getMastery();
        if (mastery < 40.0) {
            return "基础";
        }
        if (mastery > 80.0) {
            return "综合";
        }
        return "进阶";
    }
}
