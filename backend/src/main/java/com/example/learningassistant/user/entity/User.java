package com.example.learningassistant.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户（多租户：tenant_id 行级隔离，由 MyBatis-Plus 租户插件自动过滤）。
 */
@Data
@TableName("t_user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private String username;
    /** BCrypt 哈希（骨架阶段沿用 SHA-256 加盐，见 AuthService） */
    private String password;
    private String role;
    private String nickname;
    private LocalDateTime createdAt;
}
