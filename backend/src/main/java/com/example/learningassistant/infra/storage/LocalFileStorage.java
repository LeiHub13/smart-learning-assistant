package com.example.learningassistant.infra.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 本地文件存储（默认降级）：文件落在应用本地目录，配合 Nginx 静态映射访问。
 * 配置 app.infra.storage=minio 且存在 MinIO 服务时切换为 MinioFileStorage。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.infra.storage-mode", havingValue = "local", matchIfMissing = true)
public class LocalFileStorage implements FileStorage {

    @Value("${app.infra.storage.local-path:./data/files}")
    private String basePath;

    private Path base() {
        return Paths.get(basePath, "buckets");
    }

    @Override
    public String upload(String bucket, String objectName, byte[] data, String contentType) {
        try {
            Path dir = base().resolve(bucket);
            Files.createDirectories(dir);
            Path file = dir.resolve(objectName);
            Files.write(file, data);
            return bucket + "/" + objectName;
        } catch (IOException e) {
            throw new IllegalStateException("本地文件写入失败", e);
        }
    }

    @Override
    public byte[] download(String bucket, String objectName) {
        try {
            return Files.readAllBytes(base().resolve(bucket).resolve(objectName));
        } catch (IOException e) {
            throw new IllegalStateException("本地文件读取失败", e);
        }
    }

    @Override
    public void delete(String bucket, String objectName) {
        try {
            Files.deleteIfExists(base().resolve(bucket).resolve(objectName));
        } catch (IOException e) {
            log.warn("本地文件删除失败: {}/{}", bucket, objectName, e);
        }
    }

    @Override
    public String url(String bucket, String objectName) {
        return "/files/" + bucket + "/" + objectName;
    }
}
