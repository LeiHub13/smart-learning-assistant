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

    /** 可选跳转路径（如 /hall?peer=3）：前端点击通知时直接路由过去。 */
    private String link;

    /** 来源公告 id（type=announcement 时有值）：撤回公告时按它清理已扇出的通知。 */
    private Long announcementId;

    private Boolean readFlag;

    /** 定时投递时间：非空表示尚未到点，列表里不可见；投递器置空后才放行。 */
    private LocalDateTime scheduledAt;

    private LocalDateTime createdAt;
}
