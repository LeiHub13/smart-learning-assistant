package com.example.learningassistant.generate.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 生成内容记录（讲义 / 教案等）。
 */
@Data
@TableName("t_generated_content")
public class GeneratedContent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Long courseId;
    private String type;
    private String title;
    private String content;
    private LocalDateTime createdAt;
}