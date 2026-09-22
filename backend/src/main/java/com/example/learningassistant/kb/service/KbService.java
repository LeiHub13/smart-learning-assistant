package com.example.learningassistant.kb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.learningassistant.ai.PythonRagClient;
import com.example.learningassistant.common.BizException;
import com.example.learningassistant.infra.mq.MessagePublisher;
import com.example.learningassistant.infra.storage.FileStorage;
import com.example.learningassistant.kb.entity.Chunk;
import com.example.learningassistant.kb.entity.Document;
import com.example.learningassistant.kb.entity.KnowledgeBase;
import com.example.learningassistant.kb.mapper.ChunkMapper;
import com.example.learningassistant.kb.mapper.DocumentMapper;
import com.example.learningassistant.kb.mapper.KnowledgeBaseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    public static final String TOPIC_DOC_INDEX = "kb.document.index";

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
        return indexChunks(kbId, fileName, contentType,
                ragClient.parseChunks(fileName, contentType, data), data.length);
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
        log.info("文档 {} 已分块落库，chunk 数={}", doc.getId(), parts.size());
        return doc;
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
        List<Chunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<Chunk>().eq(Chunk::getDocId, docId));
        for (Chunk c : chunks) {
            chunkMapper.deleteById(c.getId());
        }
        documentMapper.deleteById(docId);
        try {
            ragClient.delete(docId, null);
        } catch (Exception e) {
            log.warn("文档 {} 向量删除失败（不影响正文已删除）: {}", docId, e.getMessage());
        }
    }

    public List<Document> documents(Long kbId) {
        return documentMapper.selectList(new LambdaQueryWrapper<Document>()
                .eq(Document::getKbId, kbId)
                .orderByDesc(Document::getCreatedAt));
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
