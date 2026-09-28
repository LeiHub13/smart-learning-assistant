package com.example.learningassistant.hall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话厅消息：实时聊天记录（WebSocket 广播 + 落库供历史回看）。
 * courseId 为空表示公共大厅，非空表示课程频道（仅课程成员可见）。
 * role 0=用户消息 1=AI 助教回复（userId=0）；sources 存 AI 回答的引用来源编号（如 "1,2,3"）。
 */
@Data
@TableName("t_hall_message")
public class HallMessage {

    /** AI 助教消息的固定 userId（无真实用户） */
    public static final long AI_USER_ID = 0L;
    /** 消息角色：用户 */
    public static final int ROLE_USER = 0;
    /** 消息角色：AI 助教 */
    public static final int ROLE_AI = 1;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private Long userId;
    private Long courseId;
    private Integer role;
    private String sources;
    private String content;
    private LocalDateTime createdAt;
}
