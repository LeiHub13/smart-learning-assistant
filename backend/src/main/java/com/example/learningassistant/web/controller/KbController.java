package com.example.learningassistant.web.controller;

import com.example.learningassistant.ai.PythonRagClient;
import com.example.learningassistant.common.ApiResponse;
import com.example.learningassistant.kb.entity.Document;
import com.example.learningassistant.kb.entity.KnowledgeBase;
import com.example.learningassistant.kb.service.KbService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
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

    /** 文档预览：按 chunk 还原全文 + 原始文件下载地址（若有）。 */
    @GetMapping("/api/kb/{kbId}/documents/{docId}/preview")
    public ApiResponse<Map<String, Object>> preview(@PathVariable Long kbId, @PathVariable Long docId) {
        return ApiResponse.ok(kbService.previewDocument(kbId, docId));
    }

    /** 片段原文批量查询：答疑「点引用跳原文」按 chunkId 取正文与所属文档名（登录即可访问，同预览口径）。 */
    @GetMapping("/api/kb/chunks")
    public ApiResponse<List<Map<String, Object>>> chunkRefs(@RequestParam String ids) {
        List<Long> chunkIds = java.util.Arrays.stream(ids.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(Long::valueOf).toList();
        return ApiResponse.ok(kbService.chunkRefs(chunkIds));
    }

    /** 生成/重新生成文档 AI 速览（通读全文提炼概括、要点、可能考点；同步等待，约十几秒）。 */
    @PostMapping("/api/kb/{kbId}/documents/{docId}/overview")
    public ApiResponse<Map<String, Object>> overview(@PathVariable Long kbId, @PathVariable Long docId) {
        return ApiResponse.ok(kbService.generateOverview(kbId, docId));
    }

    /** 文档重命名：仅改展示名，chunk 与向量索引不受影响。 */
    @PutMapping("/api/kb/{kbId}/documents/{docId}/rename")
    public ApiResponse<Document> rename(@PathVariable Long kbId, @PathVariable Long docId,
                                        @RequestBody Map<String, String> body) {
        return ApiResponse.ok(kbService.renameDocument(kbId, docId, body.get("fileName")));
    }

    /** 文档管理：跨课程/知识库汇总全部文档（含原件跳转地址与速览状态）。 */
    @GetMapping("/api/kb/docs")
    public ApiResponse<List<Map<String, Object>>> docsAll() {
        return ApiResponse.ok(kbService.docsAll());
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
            "pdf", "docx");

    @PostMapping(value = "/api/kb/{kbId}/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Object>> uploadDocument(@PathVariable Long kbId,
                                                           @RequestParam("file") MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "未命名文档" : file.getOriginalFilename();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
        if (!SUPPORTED_EXT.contains(ext)) {
            throw new com.example.learningassistant.common.BizException("仅支持 .txt/.md/.pdf/.docx 及代码文件");
        }
        final Document doc;
        try {
            // 原始字节直传 ai-service 解析并切块；解析失败时 message 已是可读原因
            doc = kbService.indexUploadedDocument(kbId, name, file.getContentType(), file.getBytes());
        } catch (IOException e) {
            throw new com.example.learningassistant.common.BizException("文件读取失败: " + e.getMessage());
        } catch (PythonRagClient.RagException e) {
            throw new com.example.learningassistant.common.BizException(e.getMessage());
        }
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
     * 手动重建 ai-service 侧向量索引：上传时索引失败、更换 Embedding 模型、或向量卷被清空后使用。
     */
    @PostMapping("/api/kb/reindex")
    public ApiResponse<Map<String, Object>> reindex() {
        int n = kbService.reindexFromChunks();
        return ApiResponse.ok(Map.of("indexed", n, "vectorCount", kbService.vectorCount()));
    }
}
