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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

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

    /** 在线用户的通知 SSE 通道：新通知落库后推刷新信号，前端收到即重拉列表与未读数。 */
    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    /** 通知实时流：注册当前用户的 SSE 通道（不过期，断线由前端重连兜底）。 */
    public SseEmitter stream(Long userId) {
        SseEmitter emitter = new SseEmitter(0L);
        List<SseEmitter> list = emitters.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable cleanup = () -> {
            List<SseEmitter> cur = emitters.get(userId);
            if (cur != null) {
                cur.remove(emitter);
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());
        return emitter;
    }

    /** 向该用户的所有在线通道推刷新信号；失败连接交由回调清理，不影响主流程。 */
    private void pushRefresh(Long userId) {
        List<SseEmitter> list = emitters.get(userId);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (SseEmitter e : list) {
            try {
                e.send(SseEmitter.event().name("refresh").data(Map.of("t", System.currentTimeMillis())));
            } catch (Exception ex) {
                try {
                    e.complete();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 发送站内通知；已启用邮件渠道且用户绑定了邮箱时同步外发邮件。
     */
    public void send(Long userId, String type, String title, String content) {
        send(userId, type, title, content, null, null, null, true);
    }

    /** 带跳转路径的通知：link 存前端路由（如 /hall?peer=3），点击通知时直接跳转。 */
    public void send(Long userId, String type, String title, String content, String link) {
        send(userId, type, title, content, null, link, null, true);
    }

    /** 公告通知：记录来源公告 id，供撤回时按它清理已扇出的通知。email=false 时不外发邮件（公告可选邮件开关）。 */
    public void send(Long userId, String type, String title, String content, Long announcementId, boolean email) {
        send(userId, type, title, content, null, null, announcementId, email);
    }

    /**
     * 发送站内通知。scheduledAt 非空表示定时通知：先落库但不放行，
     * 到点由 {@link #deliverDue()} 置空并投递（此刻才出现在列表里）。
     */
    public void send(Long userId, String type, String title, String content, LocalDateTime scheduledAt) {
        send(userId, type, title, content, scheduledAt, null, null, true);
    }

    private void send(Long userId, String type, String title, String content,
                      LocalDateTime scheduledAt, String link, Long announcementId, boolean email) {
        boolean pending = scheduledAt != null && scheduledAt.isAfter(LocalDateTime.now());
        Notification n = new Notification();
        n.setUserId(userId);
        n.setType(type);
        n.setTitle(title);
        n.setContent(content);
        n.setLink(link);
        n.setAnnouncementId(announcementId);
        n.setReadFlag(false);
        n.setScheduledAt(pending ? scheduledAt : null);
        n.setCreatedAt(LocalDateTime.now());
        notificationMapper.insert(n);

        if (pending) {
            return;
        }
        pushRefresh(userId);
        if (!email) {
            return;
        }
        mailIfAvailable(userId, type, title, content);
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
            mailIfAvailable(n.getUserId(), n.getType(), n.getTitle(), n.getContent());
            pushRefresh(n.getUserId());
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

    private void mailIfAvailable(Long userId, String type, String title, String content) {
        MailService mail = mailServiceProvider.getIfAvailable();
        if (mail == null) {
            return;
        }
        User user = userMapper.selectById(userId);
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            return;
        }
        // 用户偏好：mailMute 是「不收邮件」的通知类型清单（逗号分隔），站内通知不受影响
        String mute = user.getMailMute();
        if (mute != null && !mute.isBlank() && type != null) {
            for (String t : mute.split(",")) {
                if (type.equalsIgnoreCase(t.trim())) {
                    return;
                }
            }
        }
        mail.send(userId, user.getEmail(), "【智学助手】" + title, content);
    }

    public List<Notification> list(Long userId) {
        // createdAt 精确到秒，同一秒内的通知用 id 兜底，保证稳定的新在前
        return notificationMapper.selectList(visibleWrapper(userId)
                .orderByDesc(Notification::getCreatedAt)
                .orderByDesc(Notification::getId)
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

    /** 打开通知指向的入口（如某私聊会话）时，把该入口的未读铃铛一并清掉。 */
    public void markLinkRead(Long userId, String link) {
        List<Notification> unread = notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getLink, link)
                .eq(Notification::getReadFlag, false));
        for (Notification n : unread) {
            n.setReadFlag(true);
            notificationMapper.updateById(n);
        }
    }
}
