package com.example.learningassistant.kb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库（挂靠在课程下，包含多份文档）。
 */
@Data
@TableName("t_knowledge_base")
public class KnowledgeBase {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long courseId;
    private String name;
    private LocalDateTime createdAt;
}