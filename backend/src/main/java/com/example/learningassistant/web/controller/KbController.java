package com.example.learningassistant.web.controller;

import com.example.learningassistant.common.ApiResponse;
import com.example.learningassistant.kb.entity.Document;
import com.example.learningassistant.kb.entity.KnowledgeBase;
import com.example.learningassistant.kb.service.DocumentParser;
import com.example.learningassistant.kb.service.KbService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识库接口：知识库管理、文本文档索引与删除。
 */
@RestController
@RequiredArgsConstructor
public class KbController {

    private final KbService kbService;
    private final DocumentParser documentParser;

    @GetMapping("/api/courses/{courseId}/kb")
    public ApiResponse<List<KnowledgeBase>> kbList(@PathVariable Long courseId) {
        return ApiResponse.ok(kbService.kbList(courseId));
    }

    @PostMapping("/api/courses/{courseId}/kb")
    public ApiResponse<KnowledgeBase> createKb(@PathVariable Long courseId, @RequestBody Map<String, String> body) {
        return ApiResponse.ok(kbService.createKb(courseId, body.get("name")));
    }

    @DeleteMapping("/api/kb/{kbId}")
    public ApiResponse<Void> deleteKb(@PathVariable Long kbId) {
        kbService.deleteKb(kbId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/api/kb/{kbId}/documents")
    public ApiResponse<List<Document>> documents(@PathVariable Long kbId) {
        return ApiResponse.ok(kbService.documents(kbId));
    }

    @PostMapping("/api/kb/{kbId}/documents")
    public ApiResponse<Map<String, Object>> addDocument(@PathVariable Long kbId, @RequestBody Map<String, String> body) {
        String fileName = body.get("fileName");
        Document doc = kbService.indexTextDocument(kbId, fileName, body.get("content"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", doc.getId());
        m.put("fileName", doc.getFileName());
        m.put("chunkCount", doc.getChunkCount());
        m.put("kbName", kbService.requireKb(kbId).getName());
        return ApiResponse.ok(m);
    }

    private static final Set<String> SUPPORTED_EXT = Set.of(
            "txt", "md", "markdown", "java", "json", "xml", "yml", "yaml", "sql",
            "pdf", "doc", "docx");

    @PostMapping(value = "/api/kb/{kbId}/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Object>> uploadDocument(@PathVariable Long kbId,
                                                           @RequestParam("file") MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "未命名文档" : file.getOriginalFilename();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
        if (!SUPPORTED_EXT.contains(ext)) {
            throw new com.example.learningassistant.common.BizException("仅支持 .txt/.md/.pdf/.doc/.docx 及代码文件");
        }
        final String content;
        try {
            content = documentParser.parse(name, file.getContentType(), file.getBytes());
        } catch (IOException e) {
            throw new com.example.learningassistant.common.BizException("文件读取失败: " + e.getMessage());
        }
        Document doc = kbService.indexTextDocument(kbId, name, content);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", doc.getId());
        m.put("fileName", doc.getFileName());
        m.put("chunkCount", doc.getChunkCount());
        m.put("kbName", kbService.requireKb(kbId).getName());
        return ApiResponse.ok(m);
    }

    @DeleteMapping("/api/kb/{kbId}/documents/{docId}")
    public ApiResponse<Void> deleteDocument(@PathVariable Long kbId, @PathVariable Long docId) {
        kbService.deleteDocument(docId);
        return ApiResponse.ok(null);
    }

    /**
     * 手动重建向量库索引：内存模式重启丢索引、或 Milvus 集合因 Embedding 维度变更被重建后使用。
     */
    @PostMapping("/api/kb/reindex")
    public ApiResponse<Map<String, Object>> reindex() {
        int n = kbService.reindexFromChunks();
        return ApiResponse.ok(Map.of("indexed", n, "vectorCount", kbService.vectorCount()));
    }
}
