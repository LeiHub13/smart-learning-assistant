package com.example.learningassistant.practice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 练习明细：每题作答、判分与批改。
 */
@Data
@TableName("t_practice_question")
public class PracticeQuestion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long practiceId;
    private Long questionId;
    private String userAnswer;
    private Integer score;
    private Boolean correct;
    private String review;
    private String kpName;
}