package com.example.learningassistant.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 答疑会话。
 */
@Data
@TableName("t_chat_session")
public class ChatSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Long courseId;
    private Long kbId;
    /** 检索范围：single=只搜 kbId 这个知识库（默认），course=搜本课程全部知识库。 */
    private String kbScope;
    /** 苏格拉底引导模式：开启后 AI 不直接给答案，拆步骤反问引导。 */
    private Boolean socratic;
    /** 费曼讲解模式：角色互换，用户讲 AI 追问，讲完给评价。 */
    private Boolean feynman;
    private String title;
    private LocalDateTime createdAt;
}