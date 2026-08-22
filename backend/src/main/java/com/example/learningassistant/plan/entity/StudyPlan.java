package com.example.learningassistant.plan.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 学习计划：LLM 按目标 + 掌握度生成，逐日任务见 t_plan_task。
 */
@Data
@TableName("t_study_plan")
public class StudyPlan {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Long courseId;
    private String goal;
    private Integer days;
    private String status;
    private LocalDateTime createdAt;
}
