package com.example.learningassistant.notify.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 邮件发送日志：每封外发邮件的成败留痕（通知中心"邮箱通知"页签数据源）。
 */
@Data
@TableName("t_mail_log")
public class MailLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private Long userId;
    private String email;
    private String subject;
    private String content;
    /** SENT / FAILED */
    private String status;
    /** 失败原因（成功为空） */
    private String error;
    private LocalDateTime createdAt;
}
