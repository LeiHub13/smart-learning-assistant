package com.example.learningassistant.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 答疑会话。
 */
@Data
@TableName("t_chat_session")
public class ChatSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Long courseId;
    private Long kbId;
    /** 检索范围：single=只搜 kbId 这个知识库（默认），course=搜本课程全部知识库。 */
    private String kbScope;
    private String title;
    private LocalDateTime createdAt;
}