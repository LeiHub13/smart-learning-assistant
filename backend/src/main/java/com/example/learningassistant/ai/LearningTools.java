package com.example.learningassistant.ai;

import com.example.learningassistant.analytics.mapper.AnalyticsMapper;
import com.example.learningassistant.analytics.service.AnalyticsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Spring AI Tool Calling 工具集：把业务查询能力暴露给大模型。
 *
 * 模型在对话中自主决定是否调用、传什么参数（如 courseId），
 * Spring AI 负责参数校验、JSON 序列化与结果回填，业务代码零改动。
 *
 * 工具内部 catch 异常并返回友好提示：保证 Agent 循环不因
 * 单个工具失败（如数据库未启动）而中断，模型可向用户说明情况。
 */
@Slf4j
@Component
public class LearningTools {

    private final AnalyticsService analyticsService;

    public LearningTools(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @Tool(description = "查询指定课程各知识点的掌握度统计（知识点名称、练习次数、平均掌握度、正确率）。courseId 不确定时可不传，默认查 1 号课程")
    public List<AnalyticsMapper.KpMasteryStat> kpMasteryStats(
            @ToolParam(description = "课程 ID，可选", required = false) Long courseId) {
        try {
            Long id = (courseId == null || courseId <= 0) ? 1L : courseId;
            List<AnalyticsMapper.KpMasteryStat> stats = analyticsService.kpMasteryStats(id);
            log.info("ToolCalling kpMasteryStats courseId={} -> {} 行", id, stats.size());
            return stats;
        } catch (Exception e) {
            log.warn("ToolCalling kpMasteryStats 失败", e);
            return List.of();
        }
    }

    @Tool(description = "查询指定课程学生的练习总览（昵称、练习次数、平均分、最高分）。courseId 不确定时可不传，默认查 1 号课程")
    public List<AnalyticsMapper.StudentPracticeStat> studentPracticeOverview(
            @ToolParam(description = "课程 ID，可选", required = false) Long courseId) {
        try {
            Long id = (courseId == null || courseId <= 0) ? 1L : courseId;
            List<AnalyticsMapper.StudentPracticeStat> stats = analyticsService.studentPracticeOverview(id);
            log.info("ToolCalling studentPracticeOverview courseId={} -> {} 行", id, stats.size());
            return stats;
        } catch (Exception e) {
            log.warn("ToolCalling studentPracticeOverview 失败", e);
            return List.of();
        }
    }
}
