package com.example.learningassistant.hall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 好友关系：一行代表一次好友请求/关系。status 0=待同意，1=已同意。
 * user_id 为请求方、friend_id 为接收方；同意后双方按 (me,other)|(other,me) 双向查询。
 */
@Data
@TableName("t_friendship")
public class Friendship {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_ACCEPTED = 1;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private Long userId;
    private Long friendId;
    private Integer status;
    private LocalDateTime createdAt;
}
