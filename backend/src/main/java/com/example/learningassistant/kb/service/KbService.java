package com.example.learningassistant.kb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.learningassistant.ai.EmbeddingService;
import com.example.learningassistant.common.BizException;
import com.example.learningassistant.infra.mq.MessagePublisher;
import com.example.learningassistant.infra.storage.FileStorage;
import com.example.learningassistant.infra.vector.VectorStore;
import com.example.learningassistant.kb.entity.Chunk;
import com.example.learningassistant.kb.entity.Document;
import com.example.learningassistant.kb.entity.KnowledgeBase;
import com.example.learningassistant.kb.mapper.ChunkMapper;
import com.example.learningassistant.kb.mapper.DocumentMapper;
import com.example.learningassistant.kb.mapper.KnowledgeBaseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识库服务：知识库管理 + 文档登记 + 文本分块索引（同步）。
 *
 * 索引链路：文档文本 -> 智能分块 -> 向量化写向量库 + chunk 元数据入库
 * 检索链路：问题 -> 向量召回（见 ChatService.buildContext）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbService {

    private final KnowledgeBaseMapper kbMapper;
    private final DocumentMapper documentMapper;
    private final ChunkMapper chunkMapper;
    private final FileStorage fileStorage;
    private final VectorStore vectorStore;
    private final EmbeddingService embeddingService;
    private final MessagePublisher messagePublisher;

    public static final String TOPIC_DOC_INDEX = "kb.document.index";
    private static final int CHUNK_SIZE = 200;

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
     * 纯文本文档索引（前端粘贴文本场景）：同步分块 + 向量化 + 落库。
     */
    public Document indexTextDocument(Long kbId, String fileName, String content) {
        requireKb(kbId);
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            throw new BizException("文档内容不能为空");
        }

        Document doc = new Document();
        doc.setKbId(kbId);
        doc.setFileName(fileName);
        doc.setFileUrl(null);
        doc.setFileType("text/plain");
        doc.setFileSize((long) text.length());
        doc.setChunkCount(0);
        doc.setParseStatus("SUCCESS");
        doc.setCreatedAt(LocalDateTime.now());
        documentMapper.insert(doc);

        List<String> parts = split(text, CHUNK_SIZE);
        int idx = 0;
        for (String part : parts) {
            Chunk chunk = new Chunk();
            chunk.setDocId(doc.getId());
            chunk.setKbId(kbId);
            chunk.setContent(part);
            chunk.setIdx(idx++);
            chunkMapper.insert(chunk);
            vectorStore.put(chunk.getId(), embeddingService.embed(part));
        }
        doc.setChunkCount(parts.size());
        documentMapper.updateById(doc);
        log.info("文档 {} 已索引，chunk 数={}", doc.getId(), parts.size());
        return doc;
    }

    public void deleteDocument(Long docId) {
        List<Chunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<Chunk>().eq(Chunk::getDocId, docId));
        for (Chunk c : chunks) {
            vectorStore.remove(c.getId());
            chunkMapper.deleteById(c.getId());
        }
        documentMapper.deleteById(docId);
    }

    public List<Document> documents(Long kbId) {
        return documentMapper.selectList(new LambdaQueryWrapper<Document>()
                .eq(Document::getKbId, kbId)
                .orderByDesc(Document::getCreatedAt));
    }

    /**
     * 向量化一个文本片段并写入向量库。
     */
    public void indexChunk(Long chunkId, String content) {
        vectorStore.put(chunkId, embeddingService.embed(content));
    }

    public int vectorCount() {
        return vectorStore.size();
    }

    /**
     * 从 chunk 表重建内存向量库（重启后向量库丢失时调用）。
     */
    public void reindexFromChunks() {
        List<Chunk> chunks = chunkMapper.selectList(null);
        for (Chunk c : chunks) {
            vectorStore.put(c.getId(), embeddingService.embed(c.getContent()));
        }
        log.info("向量库重建完成，共 {} 条 chunk", chunks.size());
    }

    private List<String> split(String text, int size) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (int i = 0; i < text.length(); i += size) {
            parts.add(text.substring(i, Math.min(i + size, text.length())));
        }
        return parts;
    }
}
