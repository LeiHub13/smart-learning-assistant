package com.example.learningassistant.analytics.mapper;

import lombok.Data;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 统计查询 Mapper —— Mapper 策略示例：
 * 简单单表查询走 BaseMapper 通用方法；聚合/联表/宽表统计在这里显式定义，
 * SQL 落在 la-analytics/src/main/resources/mapper/AnalyticsMapper.xml（可审查、可调优）。
 *
 * 返回类型使用 DTO（resultType），由 map-underscore-to-camel-case 自动完成
 * 下划线列名到驼峰属性的映射（对 Map 结果不生效，故不用 Map）。
 */
public interface AnalyticsMapper {

    /** 按知识点统计掌握度（聚合查询示例，XML 实现） */
    List<KpMasteryStat> kpMasteryStats(@Param("courseId") Long courseId);

    /** 学生练习总览（联表统计示例） */
    List<StudentPracticeStat> studentPracticeOverview(@Param("courseId") Long courseId);

    /** 平台模型调用统计（LLM 成本分析） */
    List<LlmCallStat> llmCallStats(@Param("scene") String scene);

    @Data
    class KpMasteryStat {
        private String kpName;
        private Integer attempts;
        private Double avgMastery;
        private Double accuracy;
    }

    @Data
    class StudentPracticeStat {
        private String nickname;
        private Integer practiceCount;
        private Double avgScore;
        private Integer maxScore;
    }

    @Data
    class LlmCallStat {
        private String scene;
        private Integer callCount;
        private Double avgLatencyMs;
        private Integer promptTokens;
        private Integer completionTokens;
    }
}
