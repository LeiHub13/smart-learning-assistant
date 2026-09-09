package com.example.learningassistant;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * V2 模块化单体启动入口。
 *
 * @MapperScan 使用 Ant 风格通配，扫描所有业务模块的 mapper 包：
 *   com.example.learningassistant.{user,course,kb,...,analytics}.mapper
 * 简单单表查询走 BaseMapper；复杂查询（聚合/联表）在模块内 resources/mapper/*.xml 定义，
 * 由 mybatis-plus.mapper-locations=classpath*:mapper/**&#47;*.xml 加载。
 */
@SpringBootApplication
@MapperScan("com.example.learningassistant.*.mapper")
public class LearningAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(LearningAssistantApplication.class, args);
    }
}

// kb -- knowledge base 知识库
// kp -- knowledge point 知识点
// km -- knowledge mastery 知识掌握度