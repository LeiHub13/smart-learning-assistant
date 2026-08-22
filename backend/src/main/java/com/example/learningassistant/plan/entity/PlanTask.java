package com.example.learningassistant.plan.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 学习计划逐日任务：打卡即 done=true。
 */
@Data
@TableName("t_plan_task")
public class PlanTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long planId;
    private Integer dayNo;
    private LocalDate taskDate;
    private String title;
    private String tasks;
    private String focusKp;
    private Boolean done;
    private LocalDateTime doneAt;
}
