package com.example.learningassistant.recommend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.learningassistant.ai.EmbeddingService;
import com.example.learningassistant.infra.vector.VectorStore;
import com.example.learningassistant.kb.entity.Chunk;
import com.example.learningassistant.kb.entity.Document;
import com.example.learningassistant.kb.mapper.ChunkMapper;
import com.example.learningassistant.kb.mapper.DocumentMapper;
import com.example.learningassistant.practice.entity.Practice;
import com.example.learningassistant.practice.mapper.PracticeMapper;
import com.example.learningassistant.progress.entity.KnowledgeMastery;
import com.example.learningassistant.progress.mapper.KnowledgeMasteryMapper;
import com.example.learningassistant.progress.service.SpacedRepetitionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 个性化推荐（规则引擎 + 可解释理由）：
 * 1. 新手引导：课程内从未练习 → 建议先做摸底练习；
 * 2. 待复习：有效掌握度（艾宾浩斯衰减）跌破 60% 的知识点；
 * 3. 专项练习：原始掌握度薄弱（<60%）的知识点 → 建议专项做题；
 * 4. 相关资料：薄弱知识点关联的知识库片段（向量检索）。
 * 未来演进：协同过滤 + LLM 理由生成 + 点击率埋点回写（见类注释）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendService {

    private final PracticeMapper practiceMapper;
    private final KnowledgeMasteryMapper masteryMapper;
    private final SpacedRepetitionService spacedRepetitionService;
    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;
    private final ChunkMapper chunkMapper;
    private final DocumentMapper documentMapper;

    private static final double REVIEW_THRESHOLD = 60.0;
    private static final int MAX_ITEMS = 6;

    public List<Map<String, Object>> recommend(Long userId, Long courseId) {
        List<Map<String, Object>> items = new ArrayList<>();
        boolean hasPracticed = practiceMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Practice>()
                .eq(Practice::getUserId, userId)
                .eq(Practice::getCourseId, courseId)) > 0;

        List<KnowledgeMastery> masteries = masteryMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeMastery>()
                        .eq(KnowledgeMastery::getUserId, userId)
                        .eq(KnowledgeMastery::getCourseId, courseId)
                        .orderByAsc(KnowledgeMastery::getMastery));

        if (!hasPracticed) {
            items.add(item("startup", "先做一组摸底练习",
                    "你还没在「本课程」做过练习，先来一组题建立掌握度画像，后续推荐会更精准。", 100, null));
            return items;
        }

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
        List<KnowledgeMastery> weakest = masteries.stream()
                .filter(m -> m.getAttempts() > 0 && m.getMastery() < 80)
                .limit(2).toList();
        for (KnowledgeMastery m : weakest) {
            String docText = relatedDoc(m.getKpName());
            if (docText != null) {
                items.add(item("document", "资料：与「" + m.getKpName() + "」相关的知识库片段",
                        docText, m.getMastery() + 0.5, m.getKpName()));
            }
        }

        items.sort(Comparator.comparingDouble(i -> (Double) i.get("priority")));
        return items.subList(0, Math.min(items.size(), MAX_ITEMS));
    }

    /** 按知识点名称做语义检索，返回最相关的知识库片段文本（含来源文档名）。 */
    private String relatedDoc(String kpName) {
        try {
            List<VectorStore.ScoredId> hits = vectorStore.search(embeddingService.embed(kpName), 1);
            if (hits.isEmpty()) {
                return null;
            }
            Chunk chunk = chunkMapper.selectById(hits.get(0).id());
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
