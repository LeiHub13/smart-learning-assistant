package com.example.learningassistant.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话消息。
 */
@Data
@TableName("t_chat_message")
public class ChatMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;
    private String role;
    private String content;
    private String sources;
    /** 与 sources 序号一一对齐的 chunkId 串（如 "12,15"），供前端点引用跳原文；旧消息为 NULL */
    private String sourceChunks;
    private LocalDateTime createdAt;
}