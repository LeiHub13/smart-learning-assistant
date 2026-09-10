package com.example.learningassistant.exam.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 考试：课程下的一次组卷（限时，每题 10 分）。
 */
@Data
@TableName("t_exam")
public class Exam {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    /** 创建人 */
    private Long userId;
    private Long courseId;
    private String title;
    /** 考试时长（分钟），null 表示不限时 */
    private Integer durationMin;
    private Integer totalScore;
    private LocalDateTime createdAt;
}
