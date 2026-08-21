package com.example.learningassistant.user.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.learningassistant.user.entity.User;

import java.util.Optional;

/**
 * 用户 Mapper：简单单表查询走 BaseMapper 通用方法；复杂查询在 XML 中定义。
 */
public interface UserMapper extends BaseMapper<User> {

    default Optional<User> findByUsername(String username) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username)));
    }
}
