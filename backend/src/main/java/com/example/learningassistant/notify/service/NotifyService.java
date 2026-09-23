package com.example.learningassistant.notify.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.learningassistant.notify.entity.Notification;
import com.example.learningassistant.notify.mapper.NotificationMapper;
import com.example.learningassistant.user.entity.User;
import com.example.learningassistant.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
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
        send(userId, type, title, content, null);
    }

    /**
     * 发送站内通知。scheduledAt 非空表示定时通知：先落库但不放行，
     * 到点由 {@link #deliverDue()} 置空并投递（此刻才出现在列表里）。
     */
    public void send(Long userId, String type, String title, String content, LocalDateTime scheduledAt) {
        boolean pending = scheduledAt != null && scheduledAt.isAfter(LocalDateTime.now());
        Notification n = new Notification();
        n.setUserId(userId);
        n.setType(type);
        n.setTitle(title);
        n.setContent(content);
        n.setReadFlag(false);
        n.setScheduledAt(pending ? scheduledAt : null);
        n.setCreatedAt(LocalDateTime.now());
        notificationMapper.insert(n);

        if (pending) {
            return;
        }
        mailIfAvailable(userId, title, content);
    }

    /**
     * 投递到点的定时通知：补发邮件后清空 scheduledAt。
     * 注意站内可见性并不依赖本方法——{@link #visibleWrapper()} 已按时间放行到期行，
     * 调度未启用（或被多实例重复触发）时只是少一封邮件，不会漏提醒。
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 30_000)
    public void deliverDue() {
        List<Notification> due = notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                .isNotNull(Notification::getScheduledAt)
                .le(Notification::getScheduledAt, LocalDateTime.now()));
        for (Notification n : due) {
            mailIfAvailable(n.getUserId(), n.getTitle(), n.getContent());
            // updateById 默认忽略 null 字段，清空必须走显式 SET
            notificationMapper.update(null, new UpdateWrapper<Notification>()
                    .set("scheduled_at", null)
                    .eq("id", n.getId()));
        }
        if (!due.isEmpty()) {
            log.info("定时通知投递：{} 条", due.size());
        }
    }

    /** 到点的定时通知与即时通知一样可见；未到点的行留在库里不展示。 */
    private LambdaQueryWrapper<Notification> visibleWrapper(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        return new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .and(w -> w.isNull(Notification::getScheduledAt).or().le(Notification::getScheduledAt, now));
    }

    private void mailIfAvailable(Long userId, String title, String content) {
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
        return notificationMapper.selectList(visibleWrapper(userId)
                .orderByDesc(Notification::getCreatedAt)
                .last("LIMIT 50"));
    }

    public long unreadCount(Long userId) {
        return notificationMapper.selectCount(visibleWrapper(userId)
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
        // 只标记已可见的：未到点的定时通知不能被「一键已读」提前抹掉
        List<Notification> unread = notificationMapper.selectList(visibleWrapper(userId)
                .eq(Notification::getReadFlag, false));
        for (Notification n : unread) {
            n.setReadFlag(true);
            notificationMapper.updateById(n);
        }
    }
}
