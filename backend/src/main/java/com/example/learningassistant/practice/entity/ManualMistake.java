package com.example.learningassistant.practice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 错题本手动收录：错题本主体由练习/考试作答记录查询推导（无作答表），
 * 本表只记录「手动/Agent 收录」的题目；收录后重练答对即自动出本（见 MistakeService 合并规则）。
 */
@Data
@TableName("t_mistake_manual")
public class ManualMistake {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private Long userId;
    private Long courseId;
    private Long questionId;
    /** 收录时间：出本判定以它为界——收录之后重练答对才出本，之前的作答不受影响 */
    private LocalDateTime createdAt;
}
