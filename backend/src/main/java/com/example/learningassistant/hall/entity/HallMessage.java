package com.example.learningassistant.hall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话厅消息：全局共享的实时聊天记录（WebSocket 广播 + 落库供历史回看）。
 */
@Data
@TableName("t_hall_message")
public class HallMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private Long userId;
    private String content;
    private LocalDateTime createdAt;
}
