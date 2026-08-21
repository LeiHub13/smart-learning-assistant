package com.example.learningassistant.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.learningassistant.chat.entity.ChatSession;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSession> {
}