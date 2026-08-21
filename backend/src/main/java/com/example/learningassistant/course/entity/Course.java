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
    private LocalDateTime createdAt;
}
