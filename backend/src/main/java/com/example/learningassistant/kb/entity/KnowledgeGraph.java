package com.example.learningassistant.kb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库知识图谱：LLM 从该 KB 的 chunk 抽取的实体关系整图（JSON：{nodes:[], edges:[]}），
 * 一库一图（kb_id 唯一），由用户在前端按需生成/重建，不随文档变更自动更新。
 */
@Data
@TableName("t_knowledge_graph")
public class KnowledgeGraph {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private Long kbId;
    /** 整图 JSON：{"nodes":[{"name","type"}],"edges":[{"source","target","relation"}]} */
    private String content;
    private Integer nodeCount;
    private Integer edgeCount;
    /** 资料过期标记：图谱生成后知识库文档有增删时置 1，提醒用户重建 */
    private Boolean stale;
    private LocalDateTime updatedAt;
}
