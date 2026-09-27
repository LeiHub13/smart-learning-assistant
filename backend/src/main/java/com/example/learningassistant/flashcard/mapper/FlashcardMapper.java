package com.example.learningassistant.flashcard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.learningassistant.flashcard.entity.Flashcard;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FlashcardMapper extends BaseMapper<Flashcard> {
}
