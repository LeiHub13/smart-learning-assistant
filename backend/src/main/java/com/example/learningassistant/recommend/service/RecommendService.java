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
 * 个性化推荐 = 规则引擎产候选 + LLM 编排润色：
 * 1. 规则引擎基于真实学习数据生成候选条目（可解释、防幻觉）：
 *    - 新手引导：课程内从未练习 → 建议先做摸底练习（不调 LLM，直接返回）；
 *    - 待复习：有效掌握度（艾宾浩斯衰减）跌破 60% 的知识点；
 *    - 专项练习：原始掌握度薄弱（<60%）的知识点；
 *    - 相关资料：薄弱知识点关联的本课程知识库片段（向量检索，按课程 kbId 集合限定范围）。
 * 2. RECOMMEND 场景调 LLM：从候选中挑选排序、润色 title/reason（禁止编造候选之外的数据，
 *    document 条目的资料文本必须原样保留）；失败或输出不合法时回退规则结果。
 * 3. 结果经 CacheService 缓存（TTL 见 CACHE_TTL）：首页每次加载都调 LLM 开销过大，
 *    掌握度变化（练习/考试提交）时由调用方显式失效。
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
    /** LLM 输出的 type 白名单：越界条目直接丢弃，防止模型自创类型导致前端图标映射失效 */
    private static final Set<String> ITEM_TYPES = Set.of("startup", "review_kp", "practice_kp", "document");

    public List<Map<String, Object>> recommend(Long userId, Long courseId) {
        boolean hasPracticed = practiceMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Practice>()
                .eq(Practice::getUserId, userId)
                .eq(Practice::getCourseId, courseId)) > 0;
        if (!hasPracticed) {
            return List.of(item("startup", "先做一组摸底练习",
                    "你还没在「本课程」做过练习，先来一组题建立掌握度画像，后续推荐会更精准。", 100, null));
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

    /** 规则引擎候选：待复习 + 专项练习 + 相关资料，按 priority 升序（越薄弱越靠前）。 */
    private List<Map<String, Object>> ruleItems(Long userId, Long courseId) {
        List<Map<String, Object>> items = new ArrayList<>();

        List<KnowledgeMastery> masteries = masteryMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeMastery>()
                        .eq(KnowledgeMastery::getUserId, userId)
                        .eq(KnowledgeMastery::getCourseId, courseId)
                        .orderByAsc(KnowledgeMastery::getMastery));

        // 1. 待复习（衰减后的有效掌握度跌破阈值）
        for (KnowledgeMastery m : masteries) {
            if (m.getMastery() < 1) {
                continue;
            }
            double effective = spacedRepetitionService.effectiveMastery(m);
            if (effective < REVIEW_THRESHOLD) {
                items.add(item("review_kp", "复习：" + m.getKpName(),
                        "距上次练习已遗忘部分内容：原掌握度 " + Math.round(m.getMastery()) + "%，当前有效约 "
                                + Math.round(effective) + "%（每天自然衰减 10%）。建议先复习再做几道题巩固。",
                        effective, m.getKpName()));
            }
        }

        // 2. 专项练习（原始掌握度薄弱，未衰减维度）
        int practicePicks = 0;
        for (KnowledgeMastery m : masteries) {
            if (practicePicks >= 2) {
                break;
            }
            if (m.getMastery() >= REVIEW_THRESHOLD || m.getAttempts() <= 0) {
                continue;
            }
            items.add(item("practice_kp", "专项练习：" + m.getKpName(),
                    "累计正确率仅 " + Math.round(m.getMastery()) + "%（" + m.getCorrectCount() + "/"
                            + m.getAttempts() + "），建议针对该知识点做一组专项题。",
                    m.getMastery(), m.getKpName()));
            practicePicks++;
        }

        // 3. 相关资料（最薄弱知识点 → 向量检索知识库片段，最多 2 条）
        List<Long> kbIds = kbService.kbList(courseId).stream()
                .map(com.example.learningassistant.kb.entity.KnowledgeBase::getId).toList();
        List<KnowledgeMastery> weakest = masteries.stream()
                .filter(m -> m.getAttempts() > 0 && m.getMastery() < 80)
                .limit(2).toList();
        for (KnowledgeMastery m : weakest) {
            String docText = relatedDoc(m.getKpName(), kbIds);
            if (docText != null) {
                items.add(item("document", "资料：与「" + m.getKpName() + "」相关的知识库片段",
                        docText, m.getMastery() + 0.5, m.getKpName()));
            }
        }

        items.sort(Comparator.comparingDouble(i -> (Double) i.get("priority")));
        return items.subList(0, Math.min(items.size(), MAX_ITEMS));
    }

    /**
     * RECOMMEND 场景调 LLM 编排候选：挑选、排序、润色 title/reason。
     * 逐条校验（type 白名单 + title/reason 非空），有效条目不足时整体回退规则结果。
     */
    private List<Map<String, Object>> llmRefine(List<Map<String, Object>> candidates) {
        try {
            ChatModel model = modelFactory.get();
            String raw = model.complete(List.of(
                    new AIChatMessage("system", "RECOMMEND\n你是学习推荐师。根据候选条目为学生编排今日推荐："
                            + "只能从候选中挑选与排序，可润色措辞但不得编造候选之外的知识点或数据；"
                            + "资料类条目的理由必须原样保留资料文本；不超过 6 条，按紧急程度从高到低排。"
                            + "只输出 JSON 数组，每项字段 type(startup/review_kp/practice_kp/document)、title、reason、kpName。"),
                    new AIChatMessage("user", "【候选条目】" + toJson(candidates)
                            + "\n【结束】\n请生成今日推荐。")));
            List<Map<String, Object>> parsed = objectMapper.readValue(raw, new TypeReference<>() {});
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> it : parsed) {
                String type = str(it.get("type"));
                String title = str(it.get("title"));
                String reason = str(it.get("reason"));
                if (!ITEM_TYPES.contains(type) || title.isEmpty() || reason.isEmpty()) {
                    continue;
                }
                Map<String, Object> clean = new LinkedHashMap<>();
                clean.put("type", type);
                clean.put("title", title);
                clean.put("reason", reason);
                clean.put("kpName", str(it.get("kpName")));
                result.add(clean);
                if (result.size() >= MAX_ITEMS) {
                    break;
                }
            }
            if (result.isEmpty()) {
                throw new IllegalStateException("LLM 未产出有效推荐项");
            }
            return result;
        } catch (Exception e) {
            log.warn("LLM 今日推荐生成失败，回退规则推荐: {}", e.getMessage());
            return candidates;
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

    /**
     * 按知识点名称走 ai-service 语义检索，返回最相关的知识库片段文本（含来源文档名）。
     * 检索范围限定在本课程的知识库集合内——传空会退化成全库检索，把别的课程资料推荐进来。
     */
    private String relatedDoc(String kpName, List<Long> kbIds) {
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
            if (content.length() > 160) {
                content = content.substring(0, 160) + "…";
            }
            Document doc = documentMapper.selectById(chunk.getDocId());
            String from = doc != null ? "（来自《" + doc.getFileName() + "》）" : "";
            return content + from;
        } catch (Exception e) {
            log.warn("相关资料检索失败（忽略）: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> item(String type, String title, String reason, double priority, String kpName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("title", title);
        m.put("reason", reason);
        m.put("kpName", kpName);
        m.put("priority", priority);
        return m;
    }
}
