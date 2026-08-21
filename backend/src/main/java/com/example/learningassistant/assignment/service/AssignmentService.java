package com.example.learningassistant.assignment.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 作业中心服务（新增模块骨架）。
 *
 * 后续迭代：作业布置（关联课程/班级、截止时间、来源题库或 AI 生成）、
 *           学生提交（文本/附件）、自动批改 + 教师点评、截止提醒（通知模块）。
 */
@Slf4j
@Service
public class AssignmentService {

    public void createHomework(Long courseId, Long classId, String title, java.time.LocalDateTime deadline) {
        // TODO: 作业落库 + 通知推送
        log.info("作业布置占位: courseId={}, classId={}, title={}, deadline={}", courseId, classId, title, deadline);
    }
}
