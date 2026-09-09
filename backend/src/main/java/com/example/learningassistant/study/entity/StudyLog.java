package com.example.learningassistant.study.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

/**
 * 学习时长日志：按（用户, 课程, 日期）聚合的每日学习分钟数。
 * courseId 为空表示未归属具体课程的泛化学习时长（如答疑浏览）。
 */
@Data
@TableName("t_study_log")
public class StudyLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private Long userId;
    private Long courseId;
    private LocalDate studyDate;
    /** 当日累计学习分钟数（心跳累加） */
    private Integer minutes;
}
