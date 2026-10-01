package com.example.learningassistant.flashcard.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 闪卡：问对式主动记忆卡片，box 为 Leitner 盒序号（1-5，答对升盒、答错回 1），
 * dueAt 为下次到期时间（间隔随盒序拉长），到期才进入复习队列前排；NULL 视为立即到期。
 * source: chat（答疑提炼）/ doc（文档提炼）/ mistake（错题直转）。
 */
@Data
@TableName("t_flashcard")
public class Flashcard {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private Long userId;
    private Long courseId;
    /** 来源类型：chat / doc / mistake */
    private String source;
    /** 来源引用：答疑会话 id / 文档 id / 错题题目 id */
    private Long sourceId;
    /** 卡片正面（问题） */
    private String front;
    /** 卡片背面（答案） */
    private String back;
    private Integer box;
    /** 最近一次自评：ok / miss */
    private String lastResult;
    private LocalDateTime lastReviewedAt;
    private LocalDateTime dueAt;
    private LocalDateTime createdAt;
}
