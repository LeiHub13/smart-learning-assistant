package com.example.learningassistant.practice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.learningassistant.practice.entity.PracticeQuestion;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PracticeQuestionMapper extends BaseMapper<PracticeQuestion> {
}