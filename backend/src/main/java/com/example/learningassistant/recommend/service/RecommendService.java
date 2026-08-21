package com.example.learningassistant.recommend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 个性化推荐服务（新增模块骨架）。
 *
 * 后续迭代：离线计算（XXL-Job：掌握度 + 行为日志 -> 协同过滤物品相似度）、
 *           在线推荐（规则引擎薄弱点优先 + 相似题 + LLM 生成推荐理由）、
 *           推荐效果埋点（点击率、完成率）回写驱动下一轮计算。
 */
@Slf4j
@Service
public class RecommendService {

    /**
     * 为指定用户推荐练习题（骨架）：返回题目 id 列表。
     */
    public java.util.List<Long> recommendQuestions(Long userId, Long courseId, int limit) {
        // TODO: 规则引擎 + 协同过滤 + LLM 理由生成
        log.info("推荐占位: userId={}, courseId={}, limit={}", userId, courseId, limit);
        return java.util.List.of();
    }
}
