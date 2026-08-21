package com.example.learningassistant.practice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.learningassistant.practice.entity.Question;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface QuestionMapper extends BaseMapper<Question> {
}