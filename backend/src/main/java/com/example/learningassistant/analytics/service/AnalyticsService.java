package com.example.learningassistant.analytics.service;

import com.example.learningassistant.analytics.mapper.AnalyticsMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 统计分析服务（数据大屏数据源）。
 *
 * 后续迭代：XXL-Job 定时把明细聚合进宽表（t_study_stats / t_exam_analysis），
 *           大屏接口只读宽表 + Redis 缓存，避免实时聚合压库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final AnalyticsMapper analyticsMapper;

    public List<AnalyticsMapper.KpMasteryStat> kpMasteryStats(Long courseId) {
        return analyticsMapper.kpMasteryStats(courseId);
    }

    public List<AnalyticsMapper.StudentPracticeStat> studentPracticeOverview(Long courseId) {
        return analyticsMapper.studentPracticeOverview(courseId);
    }
}
