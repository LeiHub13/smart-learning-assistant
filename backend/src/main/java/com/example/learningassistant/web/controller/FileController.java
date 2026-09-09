package com.example.learningassistant.web.controller;

import com.example.learningassistant.infra.storage.FileStorage;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 存储文件读取：<img> 等标签无法携带 Authorization 头，故走路径直读。
 * 路径不在 /api 下，AuthInterceptor 不拦截；对象名含时间戳，无枚举风险。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class FileController {

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "jpg", "image/jpeg", "jpeg", "image/jpeg", "png", "image/png", "webp", "image/webp",
            "pdf", "application/pdf", "md", "text/markdown", "txt", "text/plain",
            "doc", "application/msword", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final FileStorage fileStorage;

    @GetMapping("/files/{bucket}/{objectName:.+}")
    public void download(@PathVariable String bucket, @PathVariable String objectName,
                         HttpServletResponse response) throws Exception {
        String ext = objectName.contains(".")
                ? objectName.substring(objectName.lastIndexOf('.') + 1).toLowerCase() : "";
        response.setContentType(CONTENT_TYPES.getOrDefault(ext, "application/octet-stream"));
        try {
            response.getOutputStream().write(fileStorage.download(bucket, objectName));
        } catch (Exception e) {
            log.warn("文件读取失败: {}/{} - {}", bucket, objectName, e.getMessage());
            response.setStatus(404);
        }
    }
}
