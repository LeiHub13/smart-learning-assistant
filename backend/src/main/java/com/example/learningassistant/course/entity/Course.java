package com.example.learningassistant.course.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 课程。
 */
@Data
@TableName("t_course")
public class Course {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private String name;
    private String description;
    private Long ownerId;
    private String ownerName;
    /** 是否入驻课程 Hub（1=公开可见，0=仅自己与已加入者） */
    private Integer inHub;
    private LocalDateTime createdAt;
}
