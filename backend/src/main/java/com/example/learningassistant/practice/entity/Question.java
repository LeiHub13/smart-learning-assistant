package com.example.learningassistant.practice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 题目：种子数据或 AI 生成入库（source: SEED / AI）。
 */
@Data
@TableName("t_question")
public class Question {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long courseId;
    private String type;
    private String stem;
    private String options;
    private String answer;
    private String analysis;
    private String kpName;
    private String source;
    private LocalDateTime createdAt;
}