package com.example.learningassistant.exam.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 考试作答明细：每道题的作答与判分结果。
 */
@Data
@TableName("t_exam_answer")
public class ExamAnswer {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long recordId;
    private Long questionId;
    private String userAnswer;
    private Integer score;
    private Boolean correct;
    /** AI 点评 / 规则判分说明 */
    private String review;
    private String kpName;
}
