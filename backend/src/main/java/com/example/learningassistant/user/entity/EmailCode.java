package com.example.learningassistant.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 邮箱验证码：scene + email 维度仅最新一条未用码有效，同场景发送新码时旧码作废。
 */
@Data
@TableName("t_email_code")
public class EmailCode {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private String email;
    /** register=注册验证邮箱 | reset_password=重置密码 */
    private String scene;
    private String code;
    private Boolean usedFlag;
    /** 校验失败次数（达到上限即作废，防爆破） */
    private Integer failCount;
    private LocalDateTime expiredAt;
    private LocalDateTime createdAt;
}
