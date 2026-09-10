package com.example.learningassistant.exam.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 考试记录：某用户在某场考试的一次作答。
 */
@Data
@TableName("t_exam_record")
public class ExamRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private Long examId;
    private Long userId;
    private LocalDateTime startedAt;
    /** 限时考试的截止时间（null 表示不限时） */
    private LocalDateTime deadlineAt;
    private LocalDateTime submittedAt;
    private Integer score;
    private Integer totalScore;
}
