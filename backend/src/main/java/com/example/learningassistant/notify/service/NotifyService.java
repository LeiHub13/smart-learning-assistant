package com.example.learningassistant.notify.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.learningassistant.notify.entity.Notification;
import com.example.learningassistant.notify.mapper.NotificationMapper;
import com.example.learningassistant.user.entity.User;
import com.example.learningassistant.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 通知中心：站内通知落库 + 未读查询 + 标记已读。
 * 邮件为增强渠道：MailService 未装配（未启用）或用户未绑定邮箱时自动跳过。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyService {

    private final NotificationMapper notificationMapper;
    private final UserMapper userMapper;
    private final ObjectProvider<MailService> mailServiceProvider;

    /**
     * 发送站内通知；已启用邮件渠道且用户绑定了邮箱时同步外发邮件。
     */
    public void send(Long userId, String type, String title, String content) {
        Notification n = new Notification();
        n.setUserId(userId);
        n.setType(type);
        n.setTitle(title);
        n.setContent(content);
        n.setReadFlag(false);
        n.setCreatedAt(LocalDateTime.now());
        notificationMapper.insert(n);

        MailService mail = mailServiceProvider.getIfAvailable();
        if (mail == null) {
            return;
        }
        User user = userMapper.selectById(userId);
        if (user != null && user.getEmail() != null && !user.getEmail().isBlank()) {
            mail.send(userId, user.getEmail(), "【智学助手】" + title, content);
        }
    }

    public List<Notification> list(Long userId) {
        return notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .orderByDesc(Notification::getCreatedAt)
                .last("LIMIT 50"));
    }

    public long unreadCount(Long userId) {
        return notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getReadFlag, false));
    }

    public void markRead(Long userId, Long id) {
        Notification n = notificationMapper.selectById(id);
        if (n != null && n.getUserId().equals(userId)) {
            n.setReadFlag(true);
            notificationMapper.updateById(n);
        }
    }

    public void markAllRead(Long userId) {
        List<Notification> unread = notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getReadFlag, false));
        for (Notification n : unread) {
            n.setReadFlag(true);
            notificationMapper.updateById(n);
        }
    }
}
