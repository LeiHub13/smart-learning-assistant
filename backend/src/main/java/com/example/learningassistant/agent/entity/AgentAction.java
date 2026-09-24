package com.example.learningassistant.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 待确认动作：写操作只登记 proposal，用户在界面点「确认执行」后才真正落库。
 *
 * status 流转：pending -> executed / cancelled / expired / failed。
 * payload 为服务端归一化后的 JSON 对象串（如 {kbId, kbName, title, content}），
 * 确认执行时以这里的服务端数据为准，不信任模型侧文本。
 */
@Data
@TableName("t_agent_action")
public class AgentAction {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Long courseId;
    private Long sessionId;

    /** 动作类型（白名单，如 add_material）。 */
    private String kind;

    /** 服务端归一化后的动作参数（JSON 对象串）。 */
    private String payload;

    /** 服务端模板生成的中文摘要（展示在确认卡片上）。 */
    private String summary;

    private String status;

    /** 执行结果描述（executed/failed 时写入）。 */
    private String result;

    private LocalDateTime createdAt;

    /** 确认有效期：超时后懒置为 expired。 */
    private LocalDateTime expiresAt;
}
