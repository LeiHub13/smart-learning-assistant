package com.example.learningassistant.practice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 练习记录。
 */
@Data
@TableName("t_practice")
public class Practice {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Long courseId;
    private String title;
    private Integer totalScore;
    private Integer score;
    private LocalDateTime createdAt;
}
