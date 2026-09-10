package com.example.learningassistant.exam.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 组卷题目快照：考试的固定题目清单与顺序（防止抽题随机导致复现不一致）。
 */
@Data
@TableName("t_exam_question")
public class ExamQuestion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long examId;
    private Long questionId;
    /** 本题分值（当前统一 10 分） */
    private Integer score;
    /** 展示顺序，从 1 开始 */
    private Integer sortNo;
}
