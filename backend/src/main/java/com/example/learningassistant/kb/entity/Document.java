package com.example.learningassistant.kb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库文档：原件存对象存储，文本抽取后异步分块索引（见 KbService）。
 */
@Data
@TableName("t_document")
public class Document {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long kbId;
    private String fileName;
    /** 对象存储 key（MinIO 或本地路径） */
    private String fileUrl;
    private String fileType;
    private Long fileSize;
    private Integer chunkCount;
    /** parse_status: PENDING / PARSING / SUCCESS / FAILED */
    private String parseStatus;
    private LocalDateTime createdAt;
}
