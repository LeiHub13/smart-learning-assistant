package com.example.learningassistant.user.service;

import com.example.learningassistant.common.BizException;
import com.example.learningassistant.infra.storage.FileStorage;
import com.example.learningassistant.user.entity.User;
import com.example.learningassistant.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Set;

/**
 * 头像上传：走 FileStorage 抽象（local/MinIO 可切换），旧头像尽力清理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AvatarService {

    private static final String BUCKET = "avatars";
    private static final long MAX_SIZE = 2 * 1024 * 1024L;
    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private final UserMapper userMapper;
    private final FileStorage fileStorage;

    public String upload(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择头像文件");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new BizException("头像不能超过 2MB");
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (!ALLOWED_TYPES.contains(contentType)) {
            throw new BizException("仅支持 jpg/png/webp 图片");
        }
        try {
            User user = userMapper.selectById(userId);
            if (user == null) {
                throw new BizException("用户不存在");
            }
            String ext = contentType.contains("png") ? "png" : contentType.contains("webp") ? "webp" : "jpg";
            String objectName = "u" + userId + "-" + System.currentTimeMillis() + "." + ext;
            byte[] data = file.getBytes();
            fileStorage.upload(BUCKET, objectName, data, contentType);
            String url = fileStorage.url(BUCKET, objectName);

            // 旧头像若同在本存储下，尽力清理
            deleteOldAvatar(user.getAvatar());

            user.setAvatar(url);
            userMapper.updateById(user);
            return url;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("头像上传失败: {}", e.getMessage());
            throw new BizException("头像上传失败，请重试");
        }
    }

    private void deleteOldAvatar(String avatarUrl) {
        if (avatarUrl == null || !avatarUrl.startsWith("/files/")) {
            return;
        }
        try {
            String key = avatarUrl.substring("/files/".length());
            int slash = key.indexOf('/');
            if (slash > 0) {
                fileStorage.delete(key.substring(0, slash), key.substring(slash + 1));
            }
        } catch (Exception e) {
            log.warn("旧头像清理失败（忽略）: {}", e.getMessage());
        }
    }
}
