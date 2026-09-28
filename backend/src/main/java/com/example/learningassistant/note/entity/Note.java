package com.example.learningassistant.note.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 学习笔记：用户在课程下记录的学习/练习心得，可关联知识点（可选）。
 * shared=1 表示作者已共享到课程，课程频道学习空间内成员可见（仍只读）。
 */
@Data
@TableName("t_note")
public class Note {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private Long userId;
    private Long courseId;
    /** 关联知识点（可空） */
    private String kpName;
    private String title;
    private String content;
    /** 是否共享到课程频道：0=私有 1=已共享 */
    private Integer shared;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
