package com.example.learningassistant.kb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.learningassistant.ai.PythonRagClient;
import com.example.learningassistant.common.BizException;
import com.example.learningassistant.infra.mq.MessagePublisher;
import com.example.learningassistant.infra.storage.FileStorage;
import com.example.learningassistant.kb.entity.Chunk;
import com.example.learningassistant.kb.entity.Document;
import com.example.learningassistant.kb.entity.KnowledgeBase;
import com.example.learningassistant.kb.entity.KnowledgeGraph;
import com.example.learningassistant.kb.mapper.ChunkMapper;
import com.example.learningassistant.kb.mapper.DocumentMapper;
import com.example.learningassistant.kb.mapper.KnowledgeBaseMapper;
import com.example.learningassistant.kb.mapper.KnowledgeGraphMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识库服务：知识库管理 + 文档登记 + chunk 落库（同步）。
 *
 * 分工：文档解析与分块在 ai-service 侧完成（app/parsing.py），Java 拿到 chunks 后落库；
 * t_chunk 仍是 chunk 正文的唯一数据源，向量化与检索也在 ai-service 侧（app/rag.py、app/retriever.py）。
 * 索引链路：chunk 落库 -> 通知 Python 拉取该文档并写入 Chroma（PythonRagClient.index）
 */
@Slf4j
@Service
@RequiredArgsConstructor // 给final字段自动注入依赖
public class KbService {

    private final KnowledgeBaseMapper kbMapper;
    private final DocumentMapper documentMapper;
    private final ChunkMapper chunkMapper;
    private final FileStorage fileStorage;
    private final PythonRagClient ragClient;
    private final MessagePublisher messagePublisher;
    private final com.example.learningassistant.ai.ChatModelFactory modelFactory;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final com.example.learningassistant.course.mapper.CourseMapper courseMapper;
    private final com.example.learningassistant.course.mapper.CourseUserMapper courseUserMapper;
    private final KnowledgeGraphMapper knowledgeGraphMapper;

    public static final String TOPIC_DOC_INDEX = "kb.document.index";

    /** 图谱抽取的材料与产物规模上限：控制 LLM 输入输出体量与前端渲染复杂度。 */
    private static final int KG_MATERIAL_CHARS = 16000;
    private static final int KG_MATERIAL_CHUNKS = 48;
    private static final int KG_NODE_MAX = 60;
    private static final int KG_EDGE_MAX = 150;
    private static final Set<String> KG_TYPES = Set.of("概念", "术语", "方法", "原理", "工具");

    public KnowledgeBase createKb(Long courseId, String name) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setCourseId(courseId);
        kb.setName(name);
        kb.setCreatedAt(LocalDateTime.now());
        kbMapper.insert(kb);
        return kb;
    }

    public List<KnowledgeBase> kbList(Long courseId) {
        return kbMapper.selectList(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getCourseId, courseId)
                .orderByDesc(KnowledgeBase::getCreatedAt));
    }

    @Transactional
    public void deleteKb(Long kbId) {
        requireKb(kbId);
        List<Document> docs = documents(kbId);
        for (Document doc : docs) {
            deleteDocument(doc.getId());
        }
        kbMapper.deleteById(kbId);
        knowledgeGraphMapper.delete(new LambdaQueryWrapper<KnowledgeGraph>()
                .eq(KnowledgeGraph::getKbId, kbId));
        log.info("知识库 {} 已删除，共清理 {} 个文档", kbId, docs.size());
    }

    public KnowledgeBase requireKb(Long id) {
        KnowledgeBase kb = kbMapper.selectById(id);
        if (kb == null) {
            throw new BizException("知识库不存在");
        }
        return kb;
    }

    /**
     * 登记文档并触发异步索引任务（二进制文件场景）。
     */
    public Document submitDocument(Long kbId, String fileName, String contentType, byte[] data) {
        String objectName = "kb-" + kbId + "/" + System.currentTimeMillis() + "-" + fileName;
        String key = fileStorage.upload("kb-docs", objectName, data, contentType);

        Document doc = new Document();
        doc.setKbId(kbId);
        doc.setFileName(fileName);
        doc.setFileUrl(key);
        doc.setFileType(contentType);
        doc.setFileSize((long) data.length);
        doc.setChunkCount(0);
        doc.setParseStatus("PENDING");
        doc.setCreatedAt(LocalDateTime.now());
        documentMapper.insert(doc);

        // 异步索引任务（内存 MQ 演示 / RabbitMQ 生产）
        messagePublisher.publish(TOPIC_DOC_INDEX, String.valueOf(doc.getId()));
        log.info("文档 {} 已登记，索引任务已入队", doc.getId());
        return doc;
    }

    /**
     * 纯文本文档索引（前端粘贴文本、AI 讲义、种子数据场景）：Python 切块 -> 落库 -> 向量化。
     */
    public Document indexTextDocument(Long kbId, String fileName, String content) {
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            throw new BizException("文档内容不能为空");
        }
        return indexChunks(kbId, fileName, "text/plain",
                ragClient.chunkText(text), text.length());
    }

    /**
     * 上传型文档索引（PDF/DOCX/文本文件）：原始字节交给 ai-service 解析并切块，Java 落库后触发向量化。
     *
     * @throws com.example.learningassistant.ai.PythonRagClient.RagException 解析失败，message 为可读原因
     */
    public Document indexUploadedDocument(Long kbId, String fileName, String contentType, byte[] data) {
        Document doc = indexChunks(kbId, fileName, contentType,
                ragClient.parseChunks(fileName, contentType, data), data.length);
        // 原件存对象存储（MinIO/local）：文档管理页可跳转查看原文件；失败不阻塞入库（文本检索不受影响）
        // 注意对象名不能含 "/"：FileController 的 {objectName:.+} 不跨段，扁平命名与头像约定一致
        try {
            String objectName = "kb" + kbId + "-" + doc.getId() + "-" + fileName.replaceAll("[\\\\/\\r\\n]+", "_");
            fileStorage.upload("kb-docs", objectName, data, contentType);
            doc.setFileUrl(fileStorage.url("kb-docs", objectName));
            documentMapper.updateById(doc);
            log.info("文档 {} 原件已存对象存储: {}", doc.getId(), doc.getFileUrl());
        } catch (Exception e) {
            log.warn("文档 {} 原件存储失败（不影响文本检索）: {}", doc.getId(), e.getMessage());
        }
        return doc;
    }

    private Document indexChunks(Long kbId, String fileName, String fileType,
                                 List<String> parts, long fileSize) {
        requireKb(kbId);
        if (parts == null || parts.isEmpty()) {
            throw new BizException("未能从该文档切分出任何内容块");
        }

        Document doc = new Document();
        doc.setKbId(kbId);
        doc.setFileName(fileName);
        doc.setFileUrl(null);
        doc.setFileType(fileType);
        doc.setFileSize(fileSize);
        doc.setChunkCount(0);
        doc.setParseStatus("SUCCESS");
        doc.setCreatedAt(LocalDateTime.now());
        documentMapper.insert(doc);

        int idx = 0;
        for (String part : parts) {
            Chunk chunk = new Chunk();
            chunk.setDocId(doc.getId());
            chunk.setKbId(kbId);
            chunk.setContent(part);
            chunk.setIdx(idx++);
            chunkMapper.insert(chunk);
        }
        doc.setChunkCount(parts.size());
        documentMapper.updateById(doc);
        requestIndex(kbId, doc.getId());
        // 资料有增删：已有知识图谱标记为过期（弹窗提示「建议重新生成」），重建时自动清除
        markGraphStale(kbId);
        // 索引完成后后台生成 AI 速览（要点/考点），失败静默——用户可在预览面板手动重试
        triggerOverviewAsync(doc.getId());
        log.info("文档 {} 已分块落库，chunk 数={}", doc.getId(), parts.size());
        return doc;
    }

    /** 知识库文档变更后把已有图谱标记为过期（无图谱时是空更新，无副作用）。 */
    private void markGraphStale(Long kbId) {
        if (kbId == null) {
            return;
        }
        knowledgeGraphMapper.update(null, new LambdaUpdateWrapper<KnowledgeGraph>()
                .set(KnowledgeGraph::getStale, true)
                .eq(KnowledgeGraph::getKbId, kbId));
    }

    /**
     * 后台生成文档速览：daemon 线程跑 LLM（约十几秒），不阻塞上传请求；失败只记日志。
     */
    private void triggerOverviewAsync(Long docId) {
        Thread t = new Thread(() -> {
            try {
                generateOverview(null, docId);
                log.info("文档 {} 速览已自动生成", docId);
            } catch (Exception e) {
                log.warn("文档 {} 速览自动生成失败（可在预览面板手动重试）: {}", docId, e.getMessage());
            }
        }, "doc-overview-" + docId);
        t.setDaemon(true);
        t.start();
    }

    /**
     * 生成（或重新生成）文档 AI 速览：通读全文提炼一句话概括、核心要点与可能考点，
     * 以 JSON 存入 t_document.overview。kbId 传入时校验归属（与预览同口径）。
     */
    public Map<String, Object> generateOverview(Long kbId, Long docId) {
        Document doc = documentMapper.selectById(docId);
        if (doc == null || (kbId != null && !doc.getKbId().equals(kbId))) {
            throw new BizException("文档不存在");
        }
        List<Chunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getDocId, docId)
                .orderByAsc(Chunk::getIdx));
        if (chunks.isEmpty()) {
            throw new BizException("该文档没有可分析的内容");
        }
        StringBuilder sb = new StringBuilder();
        for (Chunk c : chunks) {
            if (sb.length() >= 4000) {
                break;
            }
            sb.append(c.getContent()).append('\n');
        }
        String text = sb.length() > 4000 ? sb.substring(0, 4000) + "…（后文截断）" : sb.toString().trim();

        com.example.learningassistant.ai.ChatModel model = modelFactory.get();
        String raw = model.complete(List.of(
                new com.example.learningassistant.ai.AIChatMessage("system",
                        "DOC_OVERVIEW\n你是课程资料分析助手。通读以下课程资料文本，只输出 JSON 对象："
                                + "{\"summary\":\"一句话概括（40 字内）\",\"points\":[\"核心要点，3-6 条，每条一句话\"],"
                                + "\"examPoints\":[\"可能的考点与考察方式，2-4 条\"]}。不要输出 JSON 以外的任何文字。"),
                new com.example.learningassistant.ai.AIChatMessage("user",
                        "【资料标题】" + doc.getFileName() + "\n【资料文本】\n" + text)));

        Map<String, Object> clean;
        try {
            Map<String, Object> m = objectMapper.readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
            clean = new LinkedHashMap<>();
            clean.put("summary", clip(m.get("summary"), 120));
            clean.put("points", clipList(m.get("points"), 6, 100));
            clean.put("examPoints", clipList(m.get("examPoints"), 4, 100));
        } catch (Exception e) {
            log.warn("文档 {} 速览解析失败: {}", docId, e.getMessage());
            throw new BizException("速览生成失败，请重试");
        }
        String overviewJson;
        try {
            overviewJson = objectMapper.writeValueAsString(clean);
        } catch (Exception e) {
            throw new BizException("速览保存失败，请重试");
        }
        doc.setOverview(overviewJson);
        documentMapper.updateById(doc);
        return clean;
    }

    private static String clip(Object raw, int max) {
        String s = raw == null ? "" : String.valueOf(raw).trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    @SuppressWarnings("unchecked")
    private static List<String> clipList(Object raw, int maxItems, int maxLen) {
        List<String> result = new java.util.ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (result.size() >= maxItems) {
                    break;
                }
                String s = clip(o, maxLen);
                if (!s.isEmpty()) {
                    result.add(s);
                }
            }
        }
        return result;
    }

    /** 查询知识库已生成的知识图谱（从未生成返回 null）。 */
    public Map<String, Object> graphOf(Long kbId) {
        KnowledgeBase kb = requireKb(kbId);
        KnowledgeGraph row = knowledgeGraphMapper.selectOne(new LambdaQueryWrapper<KnowledgeGraph>()
                .eq(KnowledgeGraph::getKbId, kbId)
                .last("LIMIT 1"));
        return row == null ? null : graphView(kb, row);
    }

    /**
     * 生成（或重建）知识库知识图谱：取该 KB 各文档的 chunk 文本（按文档顺序截断）交给 LLM 抽取
     * 实体关系，归一化（节点去重、剔除断边/自环、限规模）后整图 JSON 存 t_knowledge_graph。
     * 同步等待，约十几秒（与文档速览同口径）。
     */
    public Map<String, Object> generateGraph(Long kbId) {
        KnowledgeBase kb = requireKb(kbId);
        List<Document> docs = documents(kbId);
        if (docs.isEmpty()) {
            throw new BizException("该知识库还没有资料，先上传文档再生成图谱");
        }
        StringBuilder sb = new StringBuilder();
        int taken = 0;
        material:
        for (Document doc : docs) {
            List<Chunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                    .eq(Chunk::getDocId, doc.getId())
                    .orderByAsc(Chunk::getIdx));
            for (Chunk c : chunks) {
                String piece = c.getContent();
                if (piece == null || piece.isBlank()) {
                    continue;
                }
                sb.append(piece.trim()).append('\n');
                if (++taken >= KG_MATERIAL_CHUNKS || sb.length() >= KG_MATERIAL_CHARS) {
                    break material;
                }
            }
        }
        if (sb.length() == 0) {
            throw new BizException("该知识库没有可分析的内容");
        }
        String text = sb.length() > KG_MATERIAL_CHARS
                ? sb.substring(0, KG_MATERIAL_CHARS) + "…（后文截断）" : sb.toString().trim();

        com.example.learningassistant.ai.ChatModel model = modelFactory.get();
        String raw = model.complete(List.of(
                new com.example.learningassistant.ai.AIChatMessage("system",
                        "KG_EXTRACT\n你是知识图谱构建师。通读以下课程资料文本，抽取其中的核心概念、术语、方法与原理，"
                                + "以及它们之间的关系，构建一张知识图谱。只输出 JSON 对象，不要输出任何其他文字："
                                + "{\"nodes\":[{\"name\":\"节点名\",\"type\":\"概念|术语|方法|原理|工具\"}],"
                                + "\"edges\":[{\"source\":\"节点名\",\"target\":\"节点名\",\"relation\":\"关系短语(2-8字)\"}]}。"
                                + "要求：节点 15-40 个、边 20-60 条；节点名使用资料中出现的原始术语，不要自造新词；"
                                + "边必须连接已给出的节点名，同一对节点可以有多条不同的关系；"
                                + "关系要具体（如「包含」「适用于」「对比」「前置知识」）。"),
                new com.example.learningassistant.ai.AIChatMessage("user",
                        "【知识库名称】" + kb.getName() + "\n【资料文本】\n" + text)));

        Map<String, Object> graph = parseGraph(raw);
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.get("nodes");
        List<Map<String, Object>> edges = (List<Map<String, Object>>) graph.get("edges");

        String json;
        try {
            json = objectMapper.writeValueAsString(graph);
        } catch (Exception e) {
            throw new BizException("图谱保存失败，请重试");
        }
        KnowledgeGraph row = knowledgeGraphMapper.selectOne(new LambdaQueryWrapper<KnowledgeGraph>()
                .eq(KnowledgeGraph::getKbId, kbId)
                .last("LIMIT 1"));
        if (row == null) {
            row = new KnowledgeGraph();
            row.setTenantId(1L);
            row.setKbId(kbId);
        }
        row.setContent(json);
        row.setNodeCount(nodes.size());
        row.setEdgeCount(edges.size());
        row.setStale(false);
        row.setUpdatedAt(LocalDateTime.now());
        if (row.getId() == null) {
            knowledgeGraphMapper.insert(row);
        } else {
            knowledgeGraphMapper.updateById(row);
        }
        log.info("知识库 {} 知识图谱已生成：{} 节点 / {} 边", kbId, nodes.size(), edges.size());
        return graphView(kb, row);
    }

    /** 模型输出 → 干净的 {nodes, edges}：节点按名去重、类型白名单外归一为「概念」；边剔除断边/自环/重复。 */
    private Map<String, Object> parseGraph(String raw) {
        Map<String, Object> parsed;
        try {
            parsed = objectMapper.readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("知识图谱输出解析失败: {}", clip(raw, 200));
            throw new BizException("图谱生成失败，请重试");
        }
        Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        if (parsed.get("nodes") instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m) || nodes.size() >= KG_NODE_MAX) {
                    break;
                }
                String name = clip(m.get("name"), 40);
                if (name == null || name.isBlank() || nodes.containsKey(name)) {
                    continue;
                }
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("name", name);
                String type = clip(m.get("type"), 10);
                // Set.of 白名单不接受 null，先判空再归一
                node.put("type", type != null && KG_TYPES.contains(type) ? type : "概念");
                nodes.put(name, node);
            }
        }
        List<Map<String, Object>> edges = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (parsed.get("edges") instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m) || edges.size() >= KG_EDGE_MAX) {
                    break;
                }
                String source = clip(m.get("source"), 40);
                String target = clip(m.get("target"), 40);
                String relation = clip(m.get("relation"), 20);
                if (source == null || target == null || relation == null
                        || source.equals(target)
                        || !nodes.containsKey(source) || !nodes.containsKey(target)) {
                    continue;
                }
                if (!seen.add(source + "|" + target + "|" + relation)) {
                    continue;
                }
                Map<String, Object> edge = new LinkedHashMap<>();
                edge.put("source", source);
                edge.put("target", target);
                edge.put("relation", relation);
                edges.add(edge);
            }
        }
        if (nodes.isEmpty() || edges.isEmpty()) {
            throw new BizException("未能从资料中抽取到有效的概念关系，请重试");
        }
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("nodes", new ArrayList<>(nodes.values()));
        graph.put("edges", edges);
        return graph;
    }

    /** 图谱对外视图：解析存储 JSON，带知识库名、规模与生成时间。 */
    private Map<String, Object> graphView(KnowledgeBase kb, KnowledgeGraph row) {
        Map<String, Object> content;
        try {
            content = objectMapper.readValue(row.getContent(),
                    new com.fasterxml.jackson.core.type.TypeReference<>() {
                    });
        } catch (Exception e) {
            log.warn("知识库 {} 图谱数据损坏: {}", kb.getId(), e.getMessage());
            throw new BizException("图谱数据损坏，请重新生成");
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("kbId", kb.getId());
        view.put("kbName", kb.getName());
        view.put("nodes", content.getOrDefault("nodes", List.of()));
        view.put("edges", content.getOrDefault("edges", List.of()));
        view.put("nodeCount", row.getNodeCount());
        view.put("edgeCount", row.getEdgeCount());
        view.put("stale", Boolean.TRUE.equals(row.getStale()));
        view.put("updatedAt", row.getUpdatedAt() == null ? null : row.getUpdatedAt().toString());
        return view;
    }

    /**
     * 通知 ai-service 为本文档建向量索引。失败不回滚：chunk 已在库中，
     * 下次启动的一致性检查或 POST /api/kb/reindex 可补齐索引。
     */
    private void requestIndex(Long kbId, Long docId) {
        try {
            ragClient.index(kbId, docId);
        } catch (Exception e) {
            log.warn("文档 {} 向量索引失败（可调用 POST /api/kb/reindex 重建）: {}", docId, e.getMessage());
        }
    }

    public void deleteDocument(Long docId) {
        Document doc = documentMapper.selectById(docId);
        List<Chunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<Chunk>().eq(Chunk::getDocId, docId));
        for (Chunk c : chunks) {
            chunkMapper.deleteById(c.getId());
        }
        documentMapper.deleteById(docId);
        // 资料有增删：已有知识图谱标记为过期
        markGraphStale(doc == null ? null : doc.getKbId());
        // 对象存储里的原件一并清理（fileUrl 形如 /files/{bucket}/{objectName}）
        if (doc != null && doc.getFileUrl() != null && doc.getFileUrl().startsWith("/files/")) {
            String rest = doc.getFileUrl().substring("/files/".length());
            int slash = rest.indexOf('/');
            if (slash > 0) {
                try {
                    fileStorage.delete(rest.substring(0, slash), rest.substring(slash + 1));
                } catch (Exception e) {
                    log.warn("文档 {} 原件删除失败（不影响库内清理）: {}", docId, e.getMessage());
                }
            }
        }
        try {
            ragClient.delete(docId, null);
        } catch (Exception e) {
            log.warn("文档 {} 向量删除失败（不影响正文已删除）: {}", docId, e.getMessage());
        }
    }

    /**
     * 文档管理：跨我创建/已加入的课程汇总文档，带课程与知识库名（口径同「我的课程」）。
     */
    public List<Map<String, Object>> docsAll(Long userId) {
        java.util.Set<Long> courseIds = new java.util.HashSet<>();
        courseMapper.selectList(new LambdaQueryWrapper<com.example.learningassistant.course.entity.Course>()
                        .eq(com.example.learningassistant.course.entity.Course::getOwnerId, userId))
                .forEach(c -> courseIds.add(c.getId()));
        courseUserMapper.selectList(new LambdaQueryWrapper<com.example.learningassistant.course.entity.CourseUser>()
                        .eq(com.example.learningassistant.course.entity.CourseUser::getUserId, userId))
                .forEach(cu -> courseIds.add(cu.getCourseId()));

        Map<Long, KnowledgeBase> kbById = courseIds.isEmpty() ? Map.of()
                : kbMapper.selectList(new LambdaQueryWrapper<KnowledgeBase>()
                        .in(KnowledgeBase::getCourseId, courseIds)).stream()
                .collect(java.util.stream.Collectors.toMap(KnowledgeBase::getId, k -> k, (a, b) -> a));
        Map<Long, String> courseNames = courseIds.isEmpty() ? Map.of()
                : courseMapper.selectBatchIds(courseIds).stream()
                        .collect(java.util.stream.Collectors.toMap(
                                com.example.learningassistant.course.entity.Course::getId,
                                com.example.learningassistant.course.entity.Course::getName, (a, b) -> a));
        List<Document> docs = kbById.isEmpty() ? List.of()
                : documentMapper.selectList(new LambdaQueryWrapper<Document>()
                        .in(Document::getKbId, kbById.keySet())
                        .orderByDesc(Document::getCreatedAt));
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Document d : docs) {
            KnowledgeBase kb = kbById.get(d.getKbId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("docId", d.getId());
            m.put("fileName", d.getFileName());
            m.put("kbId", d.getKbId());
            m.put("kbName", kb.getName());
            m.put("courseId", kb.getCourseId());
            m.put("courseName", courseNames.getOrDefault(kb.getCourseId(), "—"));
            m.put("fileType", d.getFileType());
            m.put("chunkCount", d.getChunkCount());
            m.put("hasOverview", d.getOverview() != null);
            m.put("fileUrl", d.getFileUrl());
            m.put("createdAt", d.getCreatedAt() == null ? null : d.getCreatedAt().toString());
            result.add(m);
        }
        return result;
    }

    public List<Document> documents(Long kbId) {
        return documentMapper.selectList(new LambdaQueryWrapper<Document>()
                .eq(Document::getKbId, kbId)
                .orderByDesc(Document::getCreatedAt));
    }

    /**
     * 文档重命名：仅改展示名（fileName），chunk 与向量索引不受影响。
     */
    public Document renameDocument(Long kbId, Long docId, String fileName) {
        Document doc = documentMapper.selectById(docId);
        if (doc == null || !doc.getKbId().equals(kbId)) {
            throw new BizException("文档不存在");
        }
        String name = fileName == null ? "" : fileName.trim();
        if (name.isEmpty()) {
            throw new BizException("文档名不能为空");
        }
        if (name.length() > 200) {
            name = name.substring(0, 200);
        }
        doc.setFileName(name);
        documentMapper.updateById(doc);
        return doc;
    }

    /**
     * 文档预览：按 chunk 顺序还原全文（所有文档统一以 chunk 落库，文本型/上传型通吃）。
     */
    public Map<String, Object> previewDocument(Long kbId, Long docId) {
        Document doc = documentMapper.selectById(docId);
        if (doc == null || !doc.getKbId().equals(kbId)) {
            throw new BizException("文档不存在");
        }
        List<Chunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getDocId, docId)
                .orderByAsc(Chunk::getIdx));
        StringBuilder sb = new StringBuilder();
        for (Chunk c : chunks) {
            sb.append(c.getContent()).append('\n');
        }
        String text = sb.length() > 100_000 ? sb.substring(0, 100_000) + "…（预览截断）" : sb.toString().trim();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("docId", doc.getId());
        result.put("fileName", doc.getFileName());
        result.put("fileType", doc.getFileType());
        result.put("chunkCount", doc.getChunkCount());
        result.put("parseStatus", doc.getParseStatus());
        result.put("text", text);
        // 上传型文档若走了原始文件存储，附原文下载地址（当前上传链路均为解析后文本，fileUrl 可能为空）
        result.put("fileUrl", doc.getFileUrl());
        return result;
    }

    /**
     * 按 chunkId 批量取片段原文 + 所属文档名（答疑「点引用跳原文」用）。
     * 登录即可访问，与文档预览同一权限口径；内容截断防单条超长。
     */
    public List<Map<String, Object>> chunkRefs(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Chunk> chunks = chunkMapper.selectBatchIds(ids.stream().distinct().limit(10).toList());
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Chunk c : chunks) {
            Document doc = documentMapper.selectById(c.getDocId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("chunkId", c.getId());
            m.put("docId", c.getDocId());
            m.put("docName", doc == null || doc.getFileName() == null ? "课程资料" : doc.getFileName());
            String content = c.getContent() == null ? "" : c.getContent();
            m.put("content", content.length() > 600 ? content.substring(0, 600) + "…" : content);
            result.add(m);
        }
        return result;
    }

    /**
     * ai-service 侧的向量条数（-1 表示 ai-service 不可达）。
     */
    public long vectorCount() {
        return ragClient.vectorCount();
    }

    public int chunkCount() {
        return chunkMapper.selectCount(null).intValue();
    }

    /**
     * 全量重建 ai-service 侧向量索引：上传时索引失败、更换 Embedding 模型、或误删后使用。
     *
     * @return 重建的向量条数
     */
    public int reindexFromChunks() {
        int n = ragClient.rebuild();
        log.info("ai-service 向量索引重建完成，共 {} 条 chunk", n);
        return n;
    }
}
