package com.example.learningassistant.web.controller;

import com.example.learningassistant.analytics.mapper.AnalyticsMapper;
import com.example.learningassistant.analytics.service.AnalyticsService;
import com.example.learningassistant.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 统计分析接口：验证 la-analytics 的 XML Mapper 复杂查询链路。
 */
@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @GetMapping("/kp-stats")
    public ApiResponse<List<AnalyticsMapper.KpMasteryStat>> kpStats(@RequestParam Long courseId) {
        return ApiResponse.ok(analyticsService.kpMasteryStats(courseId));
    }

    @GetMapping("/practice-overview")
    public ApiResponse<List<AnalyticsMapper.StudentPracticeStat>> practiceOverview(@RequestParam Long courseId) {
        return ApiResponse.ok(analyticsService.studentPracticeOverview(courseId));
    }
}
