package com.example.learningassistant.notify.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站内通知：复习提醒 / 系统消息。
 */
@Data
@TableName("t_notification")
public class Notification {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String type;
    private String title;
    private String content;
    private Boolean readFlag;

    /** 定时投递时间：非空表示尚未到点，列表里不可见；投递器置空后才放行。 */
    private LocalDateTime scheduledAt;

    private LocalDateTime createdAt;
}
