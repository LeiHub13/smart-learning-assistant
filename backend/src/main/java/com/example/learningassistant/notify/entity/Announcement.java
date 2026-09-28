package com.example.learningassistant.notify.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统公告：仅管理员可发布的全员通知，发布时向全部用户扇出一条
 * type=announcement 的站内通知（用户侧经由铃铛接收，本表保留发布存档）。
 */
@Data
@TableName("t_announcement")
public class Announcement {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private Long adminId;
    private String adminName;
    private String title;
    private String content;
    private LocalDateTime createdAt;
}
