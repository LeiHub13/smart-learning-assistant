package com.example.learningassistant.exam.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 考试中心服务（新增模块骨架）。
 *
 * 后续迭代：组卷（人工选题 / 智能组卷：知识点+难度+题型分布约束）、
 *           考试场次与作答心跳、客观题自动判分 + 主观题 LLM 预批 + 教师复核、
 *           成绩发布与成绩分析（平均分/难度/区分度/知识点得分率）。
 */
@Slf4j
@Service
public class ExamService {

    /**
     * 智能组卷（骨架）：返回试卷 id。
     */
    public Long smartCompose(Long courseId, int totalScore) {
        // TODO: 按知识点+难度约束选题
        log.info("智能组卷占位: courseId={}, totalScore={}", courseId, totalScore);
        return null;
    }
}
