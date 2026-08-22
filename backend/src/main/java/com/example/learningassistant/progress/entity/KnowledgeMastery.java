package com.example.learningassistant.progress.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识点掌握度：正确率 = correct_count / attempts。
 * last_practice_at 供间隔重复复习提醒计算衰减。
 */
@Data
@TableName("t_knowledge_mastery")
public class KnowledgeMastery {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Long courseId;
    private String kpName;
    private Double mastery;
    private Integer attempts;
    private Integer correctCount;
    private LocalDateTime lastPracticeAt;
}
