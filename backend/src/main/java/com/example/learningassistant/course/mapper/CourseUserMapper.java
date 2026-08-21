package com.example.learningassistant.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.learningassistant.course.entity.CourseUser;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CourseUserMapper extends BaseMapper<CourseUser> {
}