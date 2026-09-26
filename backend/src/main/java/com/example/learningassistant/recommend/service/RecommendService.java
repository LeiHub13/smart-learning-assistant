package com.example.learningassistant.recommend.service;

import com.example.learningassistant.ai.ChatModel;
import com.example.learningassistant.ai.ChatModelFactory;
import com.example.learningassistant.ai.AIChatMessage;
import com.example.learningassistant.ai.PythonRagClient;
import com.example.learningassistant.infra.cache.CacheService;
import com.example.learningassistant.kb.entity.Chunk;
import com.example.learningassistant.kb.entity.Document;
import com.example.learningassistant.kb.mapper.ChunkMapper;
import com.example.learningassistant.kb.mapper.DocumentMapper;
import com.example.learningassistant.practice.entity.Practice;
import com.example.learningassistant.practice.mapper.PracticeMapper;
import com.example.learningassistant.progress.entity.KnowledgeMastery;
import com.example.learningassistant.progress.mapper.KnowledgeMasteryMapper;
import com.example.learningassistant.progress.service.SpacedRepetitionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 个性化推荐 = 规则引擎产候选 + LLM 只润色理由：
 * 1. 规则引擎基于真实学习数据生成候选条目（可解释、防幻觉）：
 *    - 新手引导：课程内从未练习 → 建议先做摸底练习（不调 LLM，直接返回）；
 *    - 待复习：有效掌握度（艾宾浩斯衰减）跌破 60% 的知识点；
 *    - 专项练习：原始掌握度薄弱（<60%）的知识点；
 *    - 相关资料：薄弱知识点关联的本课程知识库片段（向量检索，按课程 kbId 集合限定范围）。
 *    相近命名的知识点（互为前缀）先归并，避免同一考点以两种写法重复推荐。
 * 2. RECOMMEND 场景调 LLM：只润色各条 reason（按 index 回写），标题/类型/顺序/条数全部由服务端定死，
 *    防止模型重排或改写口径；失败或输出不合法时逐条保留规则原文。
 * 3. 结果经 CacheService 缓存（TTL 见 CACHE_TTL）：首页每次加载都调 LLM 开销过大，
 *    掌握度变化（练习/考试提交）时由调用方显式失效。
 * 4. 每条带 action（前端路由）：复习/专项练习 → 练习页并限定该知识点抽题，让推荐可直接执行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendService {

    private final PracticeMapper practiceMapper;
    private final KnowledgeMasteryMapper masteryMapper;
    private final SpacedRepetitionService spacedRepetitionService;
    private final PythonRagClient ragClient;
    private final ChunkMapper chunkMapper;
    private final DocumentMapper documentMapper;
    private final com.example.learningassistant.kb.service.KbService kbService;
    private final ChatModelFactory modelFactory;
    private final ObjectMapper objectMapper;
    private final CacheService cacheService;

    private static final double REVIEW_THRESHOLD = 60.0;
    private static final int MAX_ITEMS = 6;
    private static final String CACHE_PREFIX = "recommend:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final int REASON_MAX = 120;
    private static final int DOC_SNIPPET_MAX = 160;
    private static final int DOC_TITLE_MAX = 60;

    public List<Map<String, Object>> recommend(Long userId, Long courseId) {
        boolean hasPracticed = practiceMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Practice>()
                .eq(Practice::getUserId, userId)
                .eq(Practice::getCourseId, courseId)) > 0;
        if (!hasPracticed) {
            return List.of(item("startup", "先做一组摸底练习",
                    "你还没在本课程做过练习，先来一组题建立掌握度画像，后续推荐会更精准。",
                    100, null, practiceAction(courseId, null), null));
        }

        String key = cacheKey(userId, courseId);
        String cached = cacheService.get(key);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, new TypeReference<>() {});
            } catch (Exception e) {
                cacheService.delete(key);
            }
        }

        List<Map<String, Object>> candidates = ruleItems(userId, courseId);
        if (candidates.isEmpty()) {
            return candidates;
        }
        List<Map<String, Object>> items = llmRefine(candidates);
        cacheService.set(key, toJson(items), CACHE_TTL);
        return items;
    }

    /** 推荐缓存键：掌握度变化的入口（练习/考试提交）据此显式失效。 */
    public static String cacheKey(Long userId, Long courseId) {
        return CACHE_PREFIX + userId + ":" + courseId;
    }

    /**
     * 规则引擎候选。相近 kpName 先归并成组（互为前缀视为同一考点，避免同一考点以两种写法重复推荐）；
     * 同一组最多产出一条候选：命中最紧急的类型（复习优先于专项练习）。
     */
    private List<Map<String, Object>> ruleItems(Long userId, Long courseId) {
        List<KnowledgeMastery> masteries = masteryMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeMastery>()
                        .eq(KnowledgeMastery::getUserId, userId)
                        .eq(KnowledgeMastery::getCourseId, courseId)
                        .orderByAsc(KnowledgeMastery::getMastery));

        List<List<KnowledgeMastery>> groups = new ArrayList<>();
        for (KnowledgeMastery m : masteries) {
            findGroup(groups, m.getKpName()).add(m);
        }

        List<Map<String, Object>> items = new ArrayList<>();
        Set<String> reviewed = new java.util.HashSet<>();

        // 1. 待复习（组内有效掌握度最低者跌破阈值）
        for (List<KnowledgeMastery> g : groups) {
            KnowledgeMastery m = g.get(0);
            if (m.getMastery() < 1) {
                continue;
            }
            double effective = spacedRepetitionService.effectiveMastery(m);
            if (effective < REVIEW_THRESHOLD) {
                String name = displayName(g);
                reviewed.add(name);
                items.add(item("review_kp", "复习：" + name,
                        "距上次练习已遗忘部分内容：原掌握度 " + Math.round(m.getMastery()) + "%，当前有效约 "
                                + Math.round(effective) + "%（每天自然衰减 10%）。先复习再做一组题巩固。",
                        effective, name, practiceAction(courseId, name), null));
            }
        }

        // 2. 专项练习（原始掌握度薄弱、且未作为复习条目出现过的组，最多 2 条）
        int practicePicks = 0;
        for (List<KnowledgeMastery> g : groups) {
            if (practicePicks >= 2) {
                break;
            }
            KnowledgeMastery m = g.get(0);
            if (m.getMastery() >= REVIEW_THRESHOLD || m.getAttempts() <= 0) {
                continue;
            }
            String name = displayName(g);
            if (reviewed.contains(name)) {
                continue;
            }
            items.add(item("practice_kp", "专项练习：" + name,
                    "累计正确率仅 " + Math.round(m.getMastery()) + "%（" + m.getCorrectCount() + "/"
                            + m.getAttempts() + "），建议针对该知识点做一组专项题。",
                    m.getMastery(), name, practiceAction(courseId, name), null));
            practicePicks++;
        }

        // 3. 相关资料（最薄弱的组 → 向量检索知识库片段，最多 2 条；标题给文档名，原文折叠进 snippet）
        List<Long> kbIds = kbService.kbList(courseId).stream()
                .map(com.example.learningassistant.kb.entity.KnowledgeBase::getId).toList();
        int docPicks = 0;
        for (List<KnowledgeMastery> g : groups) {
            if (docPicks >= 2) {
                break;
            }
            KnowledgeMastery m = g.get(0);
            if (m.getAttempts() <= 0 || m.getMastery() >= 80) {
                continue;
            }
            String name = displayName(g);
            if (reviewed.contains(name)) {
                continue;
            }
            DocRef doc = relatedDoc(name, kbIds);
            if (doc != null) {
                items.add(item("document", "资料：《" + doc.docName() + "》",
                        "「" + name + "」是当前薄弱点，读一读这篇资料再来做题效果更好。",
                        m.getMastery() + 0.5, name, null, doc.snippet()));
                docPicks++;
            }
        }

        items.sort(Comparator.comparingDouble(i -> (Double) i.get("priority")));
        // priority 只参与服务端排序，不下发前端
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> it : items.subList(0, Math.min(items.size(), MAX_ITEMS))) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("type", it.get("type"));
            out.put("title", it.get("title"));
            out.put("reason", it.get("reason"));
            out.put("kpName", it.get("kpName"));
            out.put("action", it.get("action"));
            out.put("snippet", it.get("snippet"));
            result.add(out);
        }
        return result;
    }

    /** 归并组定位：与组内首个（最薄弱）成员的 kpName 互为前缀即视为同组。 */
    private List<KnowledgeMastery> findGroup(List<List<KnowledgeMastery>> groups, String kpName) {
        String name = kpName == null ? "" : kpName.trim().toLowerCase();
        for (List<KnowledgeMastery> g : groups) {
            String key = g.get(0).getKpName() == null ? "" : g.get(0).getKpName().trim().toLowerCase();
            if (!key.isEmpty() && !name.isEmpty() && (key.startsWith(name) || name.startsWith(key))) {
                return g;
            }
        }
        List<KnowledgeMastery> g = new ArrayList<>();
        groups.add(g);
        return g;
    }

    /** 展示名：组内最短的 kpName（如「Spring Boot 自动配置」优于「…自动配置原理」）。 */
    private String displayName(List<KnowledgeMastery> g) {
        String best = g.get(0).getKpName();
        for (KnowledgeMastery m : g) {
            if (m.getKpName() != null && (best == null || m.getKpName().length() < best.length())) {
                best = m.getKpName();
            }
        }
        return best;
    }

    /** 推荐落点：练习页并限定知识点抽题（kp 为空时只带课程）。 */
    private String practiceAction(Long courseId, String kpName) {
        String path = "/practice?courseId=" + courseId;
        if (kpName != null && !kpName.isBlank()) {
            path += "&kp=" + java.net.URLEncoder.encode(kpName, java.nio.charset.StandardCharsets.UTF_8);
        }
        return path;
    }

    /** 相关资料检索结果：来源文档名 + 折叠预览的片段原文。 */
    private record DocRef(String docName, String snippet) {}

    /**
     * 按知识点名称走 ai-service 语义检索，返回最相关的知识库片段。
     * 检索范围限定在本课程的知识库集合内——传空会退化成全库检索，把别的课程资料推荐进来。
     */
    private DocRef relatedDoc(String kpName, List<Long> kbIds) {
        if (kbIds.isEmpty()) {
            return null;
        }
        try {
            List<PythonRagClient.Hit> hits = ragClient.retrieveIn(kpName, kbIds, 1);
            if (hits.isEmpty()) {
                return null;
            }
            Chunk chunk = chunkMapper.selectById(hits.get(0).chunkId());
            if (chunk == null) {
                return null;
            }
            String content = chunk.getContent();
            if (content.length() > DOC_SNIPPET_MAX) {
                content = content.substring(0, DOC_SNIPPET_MAX) + "…";
            }
            Document doc = documentMapper.selectById(chunk.getDocId());
            String docName = clip(doc != null && doc.getFileName() != null ? doc.getFileName() : "课程资料", DOC_TITLE_MAX);
            return new DocRef(docName, content);
        } catch (Exception e) {
            log.warn("相关资料检索失败（忽略）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * RECOMMEND 场景只让 LLM 润色各条 reason（按 index 回写）：标题、类型、顺序、条数全部由服务端定死，
     * 防止模型重排/改写口径导致推荐混乱；调用失败或个别条目缺失时保留规则原文。
     */
    private List<Map<String, Object>> llmRefine(List<Map<String, Object>> items) {
        try {
            List<Map<String, Object>> payload = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("index", i);
                m.put("type", items.get(i).get("type"));
                m.put("title", items.get(i).get("title"));
                m.put("reason", items.get(i).get("reason"));
                payload.add(m);
            }
            ChatModel model = modelFactory.get();
            String raw = model.complete(List.of(
                    new AIChatMessage("system", "RECOMMEND\n你是学习推荐师。候选条目已编排完毕，你只负责逐条润色 reason："
                            + "更具体、更有行动感，必须保留其中的数字与事实，不得新增知识点或数据，每条不超过 80 字。"
                            + "不要增删或重排条目。只输出 JSON 数组："
                            + "[{\"index\":条目下标,\"reason\":\"润色后的理由\"}]，条数与输入一致。"),
                    new AIChatMessage("user", "【候选条目】" + toJson(payload) + "\n【结束】\n请逐条润色 reason。")));
            List<Map<String, Object>> parsed = objectMapper.readValue(raw, new TypeReference<>() {});
            Map<Integer, String> polished = new java.util.HashMap<>();
            for (Map<String, Object> it : parsed) {
                int idx = it.get("index") instanceof Number n ? n.intValue() : -1;
                String reason = str(it.get("reason"));
                if (idx >= 0 && idx < items.size() && !reason.isEmpty()) {
                    polished.put(idx, clip(reason, REASON_MAX));
                }
            }
            if (polished.isEmpty()) {
                throw new IllegalStateException("LLM 未产出有效润色");
            }
            List<Map<String, Object>> result = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                Map<String, Object> out = new LinkedHashMap<>(items.get(i));
                if (polished.containsKey(i)) {
                    out.put("reason", polished.get(i));
                }
                result.add(out);
            }
            return result;
        } catch (Exception e) {
            log.warn("LLM 推荐润色失败，保留规则原文: {}", e.getMessage());
            return items;
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new IllegalStateException("推荐序列化失败", e);
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private Map<String, Object> item(String type, String title, String reason, double priority,
                                     String kpName, String action, String snippet) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("title", title);
        m.put("reason", reason);
        m.put("kpName", kpName);
        m.put("priority", priority);
        m.put("action", action);
        m.put("snippet", snippet);
        return m;
    }
}
