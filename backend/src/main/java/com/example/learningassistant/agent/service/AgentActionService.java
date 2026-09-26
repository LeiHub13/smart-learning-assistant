package com.example.learningassistant.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.learningassistant.agent.entity.AgentAction;
import com.example.learningassistant.agent.mapper.AgentActionMapper;
import com.example.learningassistant.common.BizException;
import com.example.learningassistant.favorite.service.FavoriteService;
import com.example.learningassistant.generate.service.GeneratorService;
import com.example.learningassistant.kb.entity.Document;
import com.example.learningassistant.kb.entity.KnowledgeBase;
import com.example.learningassistant.kb.service.KbService;
import com.example.learningassistant.note.entity.Note;
import com.example.learningassistant.note.service.NoteService;
import com.example.learningassistant.notify.service.NotifyService;
import com.example.learningassistant.plan.service.PlanService;
import com.example.learningassistant.practice.entity.Question;
import com.example.learningassistant.practice.mapper.QuestionMapper;
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
            Set.of("add_material", "schedule_review", "finish_plan_task",
                    "add_note", "favorite_question", "generate_questions", "open_page",
                    "add_plan_task", "add_question");
    /** add_question 的题型白名单（与出题链路的题型保持一致）。 */
    private static final Set<String> QUESTION_TYPES = Set.of("单选", "多选", "判断", "问答");
    private static final Set<String> DIFFICULTIES = Set.of("基础", "进阶", "综合");
    private static final int TITLE_MAX = 100;
    private static final int CONTENT_MAX = 20000;
    private static final int NOTE_CONTENT_MAX = 5000;
    private static final int SUMMARY_MAX = 300;
    private static final int RESULT_MAX = 300;
    private static final int FILE_NAME_MAX = 200;
    private static final int KP_MAX = 50;
    private static final int STEM_MAX = 60;
    private static final int NOTE_MAX = 100;
    private static final int TASKS_MAX = 500;
    private static final int STEM_INPUT_MAX = 2000;
    private static final int ANSWER_MAX = 500;
    private static final int ANALYSIS_MAX = 1000;
    private static final int OPTION_VALUE_MAX = 200;
    private static final int OPTION_KEY_MAX = 10;
    private static final int OPTIONS_MAX = 8;
    private static final long TTL_MINUTES = 30;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** open_page 白名单：页面标识 → {前端路由, 中文名}，服务端持有，模型只能从白名单里挑。 */
    private static final Map<String, String[]> PAGE_ROUTES = Map.ofEntries(
            Map.entry("home", new String[]{"/home", "首页"}),
            Map.entry("chat", new String[]{"/chat", "智能答疑"}),
            Map.entry("generate", new String[]{"/generate", "AI 内容生成"}),
            Map.entry("practice", new String[]{"/practice", "题库练习"}),
            Map.entry("mistakes", new String[]{"/mistakes", "错题本"}),
            Map.entry("favorites", new String[]{"/favorites", "收藏夹"}),
            Map.entry("exam", new String[]{"/exam", "在线考试"}),
            Map.entry("bank", new String[]{"/bank", "题库管理"}),
            Map.entry("progress", new String[]{"/progress", "学情分析"}),
            Map.entry("manage", new String[]{"/manage", "我的课程"}),
            Map.entry("hub", new String[]{"/hub", "课程广场"}),
            Map.entry("plans", new String[]{"/plans", "学习计划"}),
            Map.entry("reports", new String[]{"/reports", "学习报告"}),
            Map.entry("notes", new String[]{"/notes", "学习笔记"}),
            Map.entry("search", new String[]{"/search", "搜索"}));

    private final AgentActionMapper actionMapper;
    private final KbService kbService;
    private final NotifyService notifyService;
    private final PlanService planService;
    private final NoteService noteService;
    private final FavoriteService favoriteService;
    private final GeneratorService generatorService;
    private final QuestionMapper questionMapper;
    private final com.example.learningassistant.practice.service.QuestionService questionService;
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
            case "add_note" -> proposeAddNote(userId, courseId, sessionId, src);
            case "favorite_question" -> proposeFavorite(userId, courseId, sessionId, src);
            case "generate_questions" -> proposeGenerateQuestions(userId, courseId, sessionId, src);
            case "open_page" -> proposeOpenPage(userId, sessionId, src);
            case "add_plan_task" -> proposeAddPlanTask(userId, courseId, sessionId, src);
            case "add_question" -> proposeAddQuestion(userId, courseId, sessionId, src);
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

    private AgentAction proposeAddNote(Long userId, Long courseId, Long sessionId,
                                       Map<String, Object> src) {
        if (courseId == null) {
            throw new BizException("当前会话未绑定课程，无法记录笔记");
        }
        String title = src.get("title") == null ? "" : String.valueOf(src.get("title")).trim();
        String content = src.get("content") == null ? "" : String.valueOf(src.get("content")).trim();
        if (title.isEmpty()) {
            throw new BizException("缺少笔记标题");
        }
        if (content.isEmpty()) {
            throw new BizException("缺少笔记内容");
        }
        if (title.length() > TITLE_MAX) {
            title = title.substring(0, TITLE_MAX);
        }
        if (content.length() > NOTE_CONTENT_MAX) {
            content = content.substring(0, NOTE_CONTENT_MAX);
        }
        String kp = clip(src.get("kpName"), KP_MAX);

        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("title", title);
        stored.put("content", content);
        stored.put("kpName", kp == null || kp.isBlank() ? null : kp);

        AgentAction a = newAction(userId, courseId, sessionId, "add_note", stored,
                "记学习笔记：《" + title + "》");
        log.info("用户 {} 登记待确认动作 {}（kind=add_note）", userId, a.getId());
        return a;
    }

    private AgentAction proposeFavorite(Long userId, Long courseId, Long sessionId,
                                        Map<String, Object> src) {
        Long questionId = asLong(src.get("questionId"));
        if (questionId == null) {
            throw new BizException("缺少题目 id");
        }
        if (courseId == null) {
            throw new BizException("当前会话未绑定课程，无法收藏题目");
        }
        // 题目归属在登记阶段就校验：模型给不出跨课程题库的合法 questionId
        Question q = questionMapper.selectById(questionId);
        if (q == null) {
            throw new BizException("题目不存在");
        }
        if (!Objects.equals(q.getCourseId(), courseId)) {
            throw new BizException("题目不属于当前课程");
        }
        String stem = clip(q.getStem(), STEM_MAX);

        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("questionId", questionId);
        stored.put("stem", stem);

        AgentAction a = newAction(userId, courseId, sessionId, "favorite_question", stored,
                "收藏题目：「" + (stem == null || stem.isBlank() ? "题 #" + questionId : stem) + "」");
        log.info("用户 {} 登记待确认动作 {}（kind=favorite_question，question={}）", userId, a.getId(), questionId);
        return a;
    }

    private AgentAction proposeGenerateQuestions(Long userId, Long courseId, Long sessionId,
                                                 Map<String, Object> src) {
        if (courseId == null) {
            throw new BizException("当前会话未绑定课程，无法出题");
        }
        String kp = clip(src.get("kp"), KP_MAX);
        int count = 5;
        Object raw = src.get("count");
        if (raw instanceof Number n) {
            count = n.intValue();
        } else if (raw != null && !String.valueOf(raw).isBlank()) {
            try {
                count = Integer.parseInt(String.valueOf(raw).trim());
            } catch (NumberFormatException e) {
                throw new BizException("出题数量格式错误");
            }
        }
        if (count < 1 || count > 10) {
            throw new BizException("出题数量需在 1-10 之间");
        }

        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("kp", kp == null || kp.isBlank() ? null : kp);
        stored.put("count", count);

        AgentAction a = newAction(userId, courseId, sessionId, "generate_questions", stored,
                "AI 出题：" + count + " 道题"
                        + (kp == null || kp.isBlank() ? "" : "（知识点：" + kp + "）"));
        log.info("用户 {} 登记待确认动作 {}（kind=generate_questions，count={}）", userId, a.getId(), count);
        return a;
    }

    private AgentAction proposeOpenPage(Long userId, Long sessionId, Map<String, Object> src) {
        String page = src.get("page") == null ? "" : String.valueOf(src.get("page")).trim().toLowerCase();
        String[] route = PAGE_ROUTES.get(page);
        if (route == null) {
            throw new BizException("不支持打开该页面");
        }

        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("page", page);
        stored.put("path", route[0]);
        stored.put("label", route[1]);

        AgentAction a = newAction(userId, null, sessionId, "open_page", stored,
                "打开页面：" + route[1]);
        log.info("用户 {} 登记待确认动作 {}（kind=open_page，page={}）", userId, a.getId(), page);
        return a;
    }

    /**
     * 新建学习计划任务：目标计划在登记阶段只读解析（追加进最近一条计划），
     * 无计划时 planId 存空，确认执行时才自动创建轻量计划——登记动作本身不写任何业务表。
     */
    private AgentAction proposeAddPlanTask(Long userId, Long courseId, Long sessionId,
                                           Map<String, Object> src) {
        String title = text(src.get("title"));
        if (title.isEmpty()) {
            throw new BizException("缺少任务标题");
        }
        title = clip(title, TITLE_MAX);
        String tasks = clip(text(src.get("tasks")), TASKS_MAX);
        String focusKp = clip(src.get("focusKp"), KP_MAX);
        LocalDate date = parseTaskDate(src.get("taskDate"));

        com.example.learningassistant.plan.entity.StudyPlan plan =
                planService.list(userId).stream().findFirst().orElse(null);

        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("planId", plan == null ? null : plan.getId());
        stored.put("planGoal", plan == null ? null : clip(plan.getGoal(), TITLE_MAX));
        stored.put("title", title);
        stored.put("tasks", tasks);
        stored.put("focusKp", focusKp == null || focusKp.isBlank() ? null : focusKp);
        stored.put("taskDate", date.toString());

        AgentAction a = newAction(userId, courseId, sessionId, "add_plan_task", stored,
                "添加学习任务：《" + title + "》→ "
                        + (plan == null ? "新学习计划" : "计划「" + clip(plan.getGoal(), 30) + "」")
                        + "，日期 " + date);
        log.info("用户 {} 登记待确认动作 {}（kind=add_plan_task，plan={}）", userId, a.getId(),
                plan == null ? "新建" : plan.getId());
        return a;
    }

    /**
     * 添加题目入课程题库：题目内容在登记阶段就地归一化（题型白名单、判断题答案/选项对齐、
     * 选择题答案必须是选项之一），确认执行时经 QuestionService.create 落库。
     */
    private AgentAction proposeAddQuestion(Long userId, Long courseId, Long sessionId,
                                           Map<String, Object> src) {
        if (courseId == null) {
            throw new BizException("当前会话未绑定课程，无法添加题目");
        }
        String type = text(src.get("type"));
        if (!QUESTION_TYPES.contains(type)) {
            throw new BizException("题型仅支持：单选/多选/判断/问答");
        }
        String stem = clip(text(src.get("stem")), STEM_INPUT_MAX);
        if (stem.isEmpty()) {
            throw new BizException("缺少题干");
        }
        String answer = clip(text(src.get("answer")), ANSWER_MAX);
        if (answer.isEmpty()) {
            throw new BizException("缺少正确答案");
        }
        String analysis = clip(text(src.get("analysis")), ANALYSIS_MAX);
        String kp = clip(src.get("kpName"), KP_MAX);
        String difficulty = text(src.get("difficulty"));
        if (!DIFFICULTIES.contains(difficulty)) {
            difficulty = "进阶";
        }

        String options;
        if ("判断".equals(type)) {
            options = GeneratorService.JUDGE_OPTIONS;
            answer = GeneratorService.normalizeJudgeAnswer(answer);
            if (!"对".equals(answer) && !"错".equals(answer)) {
                throw new BizException("判断题答案应为 对/错");
            }
        } else if ("问答".equals(type)) {
            options = null;
        } else {
            List<Map<String, Object>> opts = normalizeOptions(src.get("options"));
            if (opts.size() < 2) {
                throw new BizException("单选/多选题至少需要两个选项");
            }
            options = writeValue(opts);
            java.util.Set<String> keys = opts.stream()
                    .map(o -> String.valueOf(o.get("k")).toUpperCase())
                    .collect(java.util.stream.Collectors.toSet());
            String letters = answer.replaceAll("[^A-Za-z]", "").toUpperCase();
            if (letters.isEmpty() || letters.chars().anyMatch(c -> !keys.contains(String.valueOf((char) c)))) {
                throw new BizException("正确答案需为给出的选项之一（多选可填多个字母）");
            }
            answer = letters;
        }

        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("type", type);
        stored.put("stem", stem);
        stored.put("options", options);
        stored.put("answer", answer);
        stored.put("analysis", analysis);
        stored.put("kpName", kp == null || kp.isBlank() ? null : kp);
        stored.put("difficulty", difficulty);

        AgentAction a = newAction(userId, courseId, sessionId, "add_question", stored,
                "添加题目入题库：「" + clip(stem, STEM_MAX) + "」（" + type + "）");
        log.info("用户 {} 登记待确认动作 {}（kind=add_question，type={}）", userId, a.getId(), type);
        return a;
    }

    /**
     * 选项归一化：接受 [{k,v}] 数组（与出题链路同构），k 缺省按序补 A/B/C…，截断超长项。
     * 格式不合法直接拒绝登记，不让模型侧脏数据走到确认。
     */
    private List<Map<String, Object>> normalizeOptions(Object raw) {
        if (!(raw instanceof List<?> list)) {
            throw new BizException("选择题需要提供选项数组 [{k,v}]");
        }
        if (list.size() > OPTIONS_MAX) {
            throw new BizException("选项数量过多（最多 " + OPTIONS_MAX + " 个）");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        int i = 0;
        for (Object item : list) {
            String k;
            String v;
            if (item instanceof Map<?, ?> m) {
                k = m.get("k") == null ? "" : String.valueOf(m.get("k")).trim().toUpperCase();
                v = m.get("v") == null ? "" : String.valueOf(m.get("v")).trim();
            } else {
                // 允许 ["选项A", "选项B"] 的简写形式，k 按序补字母
                v = String.valueOf(item).trim();
                k = "";
            }
            if (v.isEmpty()) {
                throw new BizException("选项内容不能为空");
            }
            if (k.isEmpty() || k.length() > OPTION_KEY_MAX) {
                k = String.valueOf((char) ('A' + i));
            }
            Map<String, Object> opt = new LinkedHashMap<>();
            opt.put("k", clip(k, OPTION_KEY_MAX));
            opt.put("v", clip(v, OPTION_VALUE_MAX));
            result.add(opt);
            i++;
        }
        return result;
    }

    /** 任务日期：留空视为今天；过去的日期明确拒绝——模型可能不知道今天几号，报错能促使其重试，避免任务排错天。 */
    private static LocalDate parseTaskDate(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            return LocalDate.now();
        }
        LocalDate date;
        try {
            date = LocalDate.parse(String.valueOf(raw).trim());
        } catch (Exception e) {
            throw new BizException("任务日期格式应为 yyyy-MM-dd");
        }
        if (date.isBefore(LocalDate.now())) {
            throw new BizException("任务日期不能早于今天（今天是 " + LocalDate.now() + "）");
        }
        return date;
    }

    /** 模型侧文本：null 安全 trim（propose 阶段高频使用）。 */
    private static String text(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
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
                case "add_note" -> executeAddNote(a, payload);
                case "favorite_question" -> executeFavorite(a, payload);
                case "generate_questions" -> executeGenerateQuestions(a, payload);
                case "open_page" -> executeOpenPage(a, payload);
                case "add_plan_task" -> executeAddPlanTask(a, payload);
                case "add_question" -> executeAddQuestion(a, payload);
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

    private void executeAddNote(AgentAction a, Map<String, Object> payload) {
        Note note = noteService.create(a.getUserId(), a.getCourseId(),
                payload.get("kpName") == null ? null : String.valueOf(payload.get("kpName")),
                String.valueOf(payload.getOrDefault("title", "")),
                String.valueOf(payload.getOrDefault("content", "")));
        payload.put("noteId", note.getId());
        a.setPayload(writeJson(payload));
        a.setStatus("executed");
        a.setResult(clip("已创建学习笔记：《" + payload.getOrDefault("title", "") + "》", RESULT_MAX));
        actionMapper.updateById(a);
        log.info("待确认动作 {} 已执行，笔记 {} 入库", a.getId(), note.getId());
    }

    private void executeFavorite(AgentAction a, Map<String, Object> payload) {
        Long questionId = asLong(payload.get("questionId"));
        if (questionId == null) {
            throw new BizException("动作数据缺少 questionId");
        }
        // 登记到确认之间题目可能已被删除；课程归属再核验一次
        Question q = questionMapper.selectById(questionId);
        if (q == null) {
            throw new BizException("题目已不存在，可能已被删除");
        }
        if (!Objects.equals(q.getCourseId(), a.getCourseId())) {
            throw new BizException("题目不属于当前课程");
        }
        boolean already = favoriteService.favoritedIds(a.getUserId(), List.of(questionId)).contains(questionId);
        if (!already) {
            favoriteService.toggle(a.getUserId(), questionId);
        }
        a.setStatus("executed");
        a.setResult(clip(already ? "该题此前已收藏，未重复操作"
                : "已收藏题目：「" + payload.getOrDefault("stem", "题 #" + questionId) + "」", RESULT_MAX));
        actionMapper.updateById(a);
        log.info("待确认动作 {} 已执行，收藏题目 {}（此前已收藏={}）", a.getId(), questionId, already);
    }

    private void executeGenerateQuestions(AgentAction a, Map<String, Object> payload) {
        int count = payload.get("count") instanceof Number n ? n.intValue() : 5;
        // LLM 出题耗时较长（确认后前端会转圈等待）；事务边界由 GeneratorService 自己保证
        List<Question> questions = generatorService.generateQuestions(a.getUserId(), a.getCourseId(),
                payload.get("kp") == null ? null : String.valueOf(payload.get("kp")), count);
        payload.put("questionIds", questions.stream().map(Question::getId).toList());
        payload.put("generated", questions.size());
        // 出完题直接引导进练习页（auto=1 让练习页自动抽题开练）
        payload.put("navigate", "/practice?courseId=" + a.getCourseId() + "&auto=1");
        a.setPayload(writeJson(payload));
        a.setStatus("executed");
        a.setResult(clip("已生成 " + questions.size() + " 道题并加入课程题库，正在前往练习", RESULT_MAX));
        actionMapper.updateById(a);
        log.info("待确认动作 {} 已执行，生成题目 {} 道", a.getId(), questions.size());
    }

    private void executeOpenPage(AgentAction a, Map<String, Object> payload) {
        // 导航动作没有服务端写入：path 在登记时就由白名单生成，确认后由前端跳转
        a.setStatus("executed");
        a.setResult(clip("已打开「" + payload.getOrDefault("label", "") + "」页面", RESULT_MAX));
        actionMapper.updateById(a);
        log.info("待确认动作 {} 已执行，跳转 {}", a.getId(), payload.get("path"));
    }

    private void executeAddPlanTask(AgentAction a, Map<String, Object> payload) {
        Long planId = asLong(payload.get("planId"));
        LocalDate date;
        try {
            date = LocalDate.parse(String.valueOf(payload.get("taskDate")));
        } catch (Exception e) {
            throw new BizException("任务日期数据损坏，请重新发起");
        }
        // planId 是登记时的只读快照：期间计划可能被删除，归属由 addTask 再校验一次；
        // 为空说明登记时用户还没有计划，这里自动创建轻量计划承接
        com.example.learningassistant.plan.entity.PlanTask task = planService.addTask(
                a.getUserId(), a.getCourseId(), planId, date,
                String.valueOf(payload.getOrDefault("title", "")),
                payload.get("tasks") == null ? null : String.valueOf(payload.get("tasks")),
                payload.get("focusKp") == null ? null : String.valueOf(payload.get("focusKp")));
        payload.put("taskId", task.getId());
        a.setPayload(writeJson(payload));
        a.setStatus("executed");
        a.setResult(clip("已添加学习任务：《" + payload.getOrDefault("title", "") + "》"
                + "（" + payload.get("taskDate") + "）", RESULT_MAX));
        actionMapper.updateById(a);
        log.info("待确认动作 {} 已执行，计划任务 {} 落库", a.getId(), task.getId());
    }

    private void executeAddQuestion(AgentAction a, Map<String, Object> payload) {
        Question q = questionService.create(a.getUserId(), a.getCourseId(),
                String.valueOf(payload.getOrDefault("type", "单选")),
                String.valueOf(payload.getOrDefault("stem", "")),
                payload.get("options") == null ? null : String.valueOf(payload.get("options")),
                String.valueOf(payload.getOrDefault("answer", "")),
                payload.get("analysis") == null ? null : String.valueOf(payload.get("analysis")),
                payload.get("kpName") == null ? null : String.valueOf(payload.get("kpName")),
                String.valueOf(payload.getOrDefault("difficulty", "进阶")));
        payload.put("questionId", q.getId());
        a.setPayload(writeJson(payload));
        a.setStatus("executed");
        a.setResult(clip("已把题目加入课程题库（题 id=" + q.getId() + "），可在题库管理中查看",
                RESULT_MAX));
        actionMapper.updateById(a);
        log.info("待确认动作 {} 已执行，题目 {} 入库", a.getId(), q.getId());
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
        // 导航类动作（open_page / generate_questions 出完题）确认后前端按此跳转
        data.put("navigate", payload.get("navigate") == null ? null : String.valueOf(payload.get("navigate")));
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

    /** 选项数组的 JSON 序列化（写入 payload 前归一化为字符串，执行时原样传给 QuestionService）。 */
    private String writeValue(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
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
            throw new BizException("参数格式错误");
        }
    }
}
