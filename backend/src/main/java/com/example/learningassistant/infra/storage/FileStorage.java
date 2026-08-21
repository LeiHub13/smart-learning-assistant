package com.example.learningassistant.infra.storage;

/**
 * 对象存储抽象：文档原件、讲义附件、头像等。
 * 实现可降级切换（local / minio）。
 */
public interface FileStorage {

    /** 上传并返回对象 key */
    String upload(String bucket, String objectName, byte[] data, String contentType);

    byte[] download(String bucket, String objectName);

    void delete(String bucket, String objectName);

    /** 生成可访问 URL */
    String url(String bucket, String objectName);
}
