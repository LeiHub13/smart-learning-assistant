package com.example.learningassistant.kb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 文档分块：文本抽取后切分落库。正文的唯一数据源，ai-service 据此建向量索引。
 */
@Data
@TableName("t_chunk")
public class Chunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long docId;
    private Long kbId;
    private String content;
    private Integer idx;
}