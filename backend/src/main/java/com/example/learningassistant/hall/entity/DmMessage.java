package com.example.learningassistant.hall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一对一私信：sender_id -> receiver_id；read_flag 表示收件人是否已读。
 */
@Data
@TableName("t_dm_message")
public class DmMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private Long senderId;
    private Long receiverId;
    private String content;
    private Integer readFlag;
    private LocalDateTime createdAt;
}
