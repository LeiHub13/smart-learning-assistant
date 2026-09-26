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
    private final com.example.learningassistant.course.mapper.CourseMapper courseMapper;
    private final com.example.learningassistant.kb.service.KbService kbService;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    /**
     * 讲义存入课程知识库：无知识库时自动创建，走文本索引链路（分块 + 向量化）。
     */
    public Map<String, Object> toKnowledgeBase(Long userId, Long contentId) {
        GeneratedContent g = contentDetail(userId, contentId);
        if (!"lecture".equals(g.getType())) {
            throw new com.example.learningassistant.common.BizException("仅讲义可存入知识库");
        }
        if (g.getCourseId() == null) {
            throw new com.example.learningassistant.common.BizException("该讲义未关联课程");
        }
        com.example.learningassistant.course.entity.Course course = courseMapper.selectById(g.getCourseId());
        if (course == null) {
            throw new com.example.learningassistant.common.BizException("课程不存在");
        }
        com.example.learningassistant.kb.entity.KnowledgeBase kb;
        java.util.List<com.example.learningassistant.kb.entity.KnowledgeBase> kbs = kbService.kbList(g.getCourseId());
        if (kbs.isEmpty()) {
            kb = kbService.createKb(g.getCourseId(), course.getName() + " · AI 讲义");
        } else {
            kb = kbs.get(0);
        }
        String title = g.getTitle() == null || g.getTitle().isBlank() ? "未命名讲义" : g.getTitle();
        com.example.learningassistant.kb.entity.Document doc =
                kbService.indexTextDocument(kb.getId(), "讲义-" + title + ".md", g.getContent());
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("kbId", kb.getId());
        m.put("kbName", kb.getName());
        m.put("docId", doc.getId());
        return m;
    }

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
     *
     * LLM 调用在事务外（耗时且不该持有连接），写库部分整体一个事务：
     * 中途失败不能留下半批题目——否则重试会叠加重复题，练习记录也指向不存在的题目。
     */
    public List<Question> generateQuestions(Long userId, Long courseId, String kp, int count) {
        String difficulty = determineDifficulty(userId, courseId, kp);
        ChatModel model = modelFactory.get();
        String raw = model.complete(List.of(
                new AIChatMessage("system",
                        "GEN_QUESTIONS\n你是出题老师，只输出 JSON 数组，字段: type(单选/多选/判断/问答), stem, options(数组[{k,v}]), answer, analysis, kpName, difficulty。难度要求：" + difficulty),
                new AIChatMessage("user", "知识点:" + (kp == null || kp.isBlank() ? "核心概念" : kp)
                        + "\n数量:" + count + "\n难度:" + difficulty + "\n请生成练习题。")));
        List<Map<String, Object>> parsed = parseQuestionArray(raw);
        return persistQuestions(userId, courseId, kp, difficulty, raw, parsed, "AI 生成练习（" + parsed.size() + " 题）");
    }

    /**
     * 错题变式（举一反三）：以学生做错的题为参照，围绕同一考点出情境/数据/角度不同的变式题。
     * 变式题与普通 AI 题同构入库，作答后走统一判分与掌握度更新；难度按该知识点掌握度自适应。
     */
    public List<Question> generateVariants(Long userId, Long questionId, int count) {
        Question origin = questionMapper.selectById(questionId);
        if (origin == null) {
            throw new com.example.learningassistant.common.BizException("原题不存在或已被删除");
        }
        String kp = origin.getKpName();
        String difficulty = determineDifficulty(userId, origin.getCourseId(), kp);
        ChatModel model = modelFactory.get();
        String raw = model.complete(List.of(
                new AIChatMessage("system",
                        "GEN_QUESTIONS\n你是出题老师，只输出 JSON 数组，字段: type(单选/多选/判断/问答), stem, options(数组[{k,v}]), answer, analysis, kpName, difficulty。难度要求：" + difficulty),
                new AIChatMessage("user",
                        "一位学生做错了下面的题，请围绕同一知识点出 " + count + " 道变式题帮他举一反三："
                                + "考查点与原题一致，但情境、数据或提问角度必须不同，不得与原题雷同；题型与选项个数尽量与原题保持一致。\n"
                                + "原题:" + origin.getStem() + "\n"
                                + "原题选项:" + (origin.getOptions() == null ? "无" : origin.getOptions()) + "\n"
                                + "原题正确答案:" + origin.getAnswer() + "\n"
                                + "原题解析:" + (origin.getAnalysis() == null || origin.getAnalysis().isBlank() ? "无" : origin.getAnalysis()) + "\n"
                                + "知识点:" + (kp == null || kp.isBlank() ? "核心概念" : kp)
                                + "\n难度:" + difficulty + "\n请生成变式题。")));
        List<Map<String, Object>> parsed = parseQuestionArray(raw);
        String title = "错题变式（" + parsed.size() + " 题）· " + (kp == null || kp.isBlank() ? "未知考点" : kp);
        return persistQuestions(userId, origin.getCourseId(), kp, difficulty, raw, parsed, title);
    }

    /** 解析 LLM 出题返回（只接受 JSON 数组），为空视为未出题。 */
    private List<Map<String, Object>> parseQuestionArray(String raw) {
        List<Map<String, Object>> parsed;
        try {
            parsed = objectMapper.readValue(raw, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            log.warn("AI 出题返回非 JSON 数组: {}", raw);
            throw new com.example.learningassistant.common.BizException("AI 出题解析失败，请重试");
        }
        if (parsed.isEmpty()) {
            throw new com.example.learningassistant.common.BizException("AI 未生成题目，请重试");
        }
        return parsed;
    }

    /**
     * 题目 + 生成记录入库。LLM 调用在事务外（耗时且不该持有连接），写库部分整体一个事务：
     * 中途失败不能留下半批题目——否则重试会叠加重复题，练习记录也指向不存在的题目。
     */
    private List<Question> persistQuestions(Long userId, Long courseId, String kp, String difficulty,
                                            String raw, List<Map<String, Object>> parsed, String contentTitle) {
        List<Question> saved;
        try {
            saved = transactionTemplate.execute(status -> {
                List<Question> rows = new java.util.ArrayList<>();
                for (Map<String, Object> m : parsed) {
                    Question q = new Question();
                    q.setCourseId(courseId);
                    String type = String.valueOf(m.getOrDefault("type", "单选"));
                    q.setType(type);
                    q.setStem(String.valueOf(m.getOrDefault("stem", "")));
                    Object opts = m.get("options");
                    q.setOptions(opts == null ? null : writeOptions(opts));
                    q.setAnswer(m.get("answer") == null ? null : String.valueOf(m.get("answer")));
                    q.setAnalysis(m.get("analysis") == null ? null : String.valueOf(m.get("analysis")));
                    q.setKpName(String.valueOf(m.getOrDefault("kpName", kp)));
                    q.setDifficulty(String.valueOf(m.getOrDefault("difficulty", difficulty)));
                    q.setSource("AI");
                    q.setCreatedAt(LocalDateTime.now());
                    sanitizeJudge(q);
                    questionMapper.insert(q);
                    rows.add(q);
                }
                GeneratedContent g = new GeneratedContent();
                g.setUserId(userId);
                g.setCourseId(courseId);
                g.setType("questions");
                g.setTitle(contentTitle);
                g.setContent(raw);
                g.setCreatedAt(LocalDateTime.now());
                contentMapper.insert(g);
                return rows;
            });
        } catch (com.example.learningassistant.common.BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("AI 出题入库失败，本次批量写入已回滚: {}", e.getMessage(), e);
            throw new com.example.learningassistant.common.BizException("AI 出题保存失败，请重试");
        }
        return saved == null ? List.of() : saved;
    }

    /** options 由 LLM 给出，序列化不了说明该题格式非法：抛出后整批回滚，不写坏数据。 */
    private String writeOptions(Object opts) {
        try {
            return objectMapper.writeValueAsString(opts);
        } catch (Exception e) {
            throw new com.example.learningassistant.common.BizException("AI 出题选项格式异常，请重试");
        }
    }

    /**
     * 判断题兜底：LLM 常不给 options（会导致答题页无选项可点）或答案写"正确/错误"（与选项 k=对/错 判分对不上）。
     * 入库前统一：options 缺省注入标准 对/错 两项，答案归一化为 对/错。
     */
    private void sanitizeJudge(Question q) {
        if (!"判断".equals(q.getType())) {
            return;
        }
        if (q.getOptions() == null || q.getOptions().isBlank()) {
            q.setOptions("[{\"k\":\"对\",\"v\":\"正确\"},{\"k\":\"错\",\"v\":\"错误\"}]");
        }
        String a = q.getAnswer() == null ? "" : q.getAnswer().trim();
        if (a.matches("(?i)(对|正确|是|T|TRUE|Y|√)")) {
            q.setAnswer("对");
        } else if (a.matches("(?i)(错|错误|否|不对|F|FALSE|N|×)")) {
            q.setAnswer("错");
        }
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
                ignored -> {
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
