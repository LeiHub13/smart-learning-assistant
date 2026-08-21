package com.example.learningassistant.course.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 课程-学生选课关系。
 */
@Data
@TableName("t_course_user")
public class CourseUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long courseId;
    private Long userId;
    private LocalDateTime joinedAt;
}