package com.example.learningassistant.infra.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * MinIO 对象存储实现：配置 app.infra.storage=minio 且存在 MinIO 服务时启用。
 * 兼容 S3 协议，可无缝切换阿里云 OSS / 腾讯云 COS。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.infra.storage-mode", havingValue = "minio")
public class MinioFileStorage implements FileStorage {

    private final MinioClient client;

    public MinioFileStorage(
            @Value("${app.infra.storage.minio.endpoint}") String endpoint,
            @Value("${app.infra.storage.minio.access-key}") String accessKey,
            @Value("${app.infra.storage.minio.secret-key}") String secretKey) {
        this.client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }

    @Override
    public String upload(String bucket, String objectName, byte[] data, String contentType) {
        try {
            ensureBucket(bucket);
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket).object(objectName)
                    .stream(new ByteArrayInputStream(data), data.length, -1)
                    .contentType(contentType)
                    .build());
            return bucket + "/" + objectName;
        } catch (Exception e) {
            throw new IllegalStateException("MinIO 上传失败", e);
        }
    }

    @Override
    public byte[] download(String bucket, String objectName) {
        try (InputStream in = client.getObject(GetObjectArgs.builder()
                .bucket(bucket).object(objectName).build())) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException("MinIO 下载失败", e);
        }
    }

    @Override
    public void delete(String bucket, String objectName) {
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket).object(objectName).build());
        } catch (Exception e) {
            throw new IllegalStateException("MinIO 删除失败", e);
        }
    }

    @Override
    public String url(String bucket, String objectName) {
        // 与 LocalFileStorage 统一路径：走 FileController 的 /files 直读（download 经 MinIO 客户端取回）
        return "/files/" + bucket + "/" + objectName;
    }

    private void ensureBucket(String bucket) throws Exception {
        boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }
}
