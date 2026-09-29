package com.example.learningassistant.notify.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.learningassistant.common.BizException;
import com.example.learningassistant.notify.entity.Announcement;
import com.example.learningassistant.notify.entity.Notification;
import com.example.learningassistant.notify.mapper.AnnouncementMapper;
import com.example.learningassistant.notify.mapper.NotificationMapper;
import com.example.learningassistant.user.entity.User;
import com.example.learningassistant.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 公告服务：仅管理员可发布。发布即向除发布者外的全部用户扇出一条
 * type=announcement 的站内通知（铃铛「系统通知」直达，未读/已读复用现有通知体系）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementService {

    public static final String NOTIFY_TYPE = "announcement";

    private final AnnouncementMapper announcementMapper;
    private final NotificationMapper notificationMapper;
    private final UserMapper userMapper;
    private final NotifyService notifyService;

    /** 发布公告：落存档 + 全员扇出站内通知（跳过发布者本人）。 */
    public Announcement publish(Long adminId, String adminName, String title, String content) {
        if (title == null || title.isBlank()) {
            throw new BizException("公告标题不能为空");
        }
        if (title.length() > 100) {
            throw new BizException("公告标题过长（≤100 字）");
        }
        if (content == null || content.isBlank()) {
            throw new BizException("公告内容不能为空");
        }
        if (content.length() > 5000) {
            throw new BizException("公告内容过长（≤5000 字）");
        }

        Announcement a = new Announcement();
        a.setTenantId(1L);
        a.setAdminId(adminId);
        a.setAdminName(adminName);
        a.setTitle(title.trim());
        a.setContent(content.trim());
        a.setCreatedAt(LocalDateTime.now());
        announcementMapper.insert(a);

        List<User> users = userMapper.selectList(new LambdaQueryWrapper<User>().select(User::getId));
        int sent = 0;
        for (User u : users) {
            if (u.getId().equals(adminId)) {
                continue;
            }
            notifyService.send(u.getId(), NOTIFY_TYPE, "公告：" + title.trim(), content.trim(), a.getId());
            sent++;
        }
        log.info("公告已发布 adminId={} 触达 {} 位用户: {}", adminId, sent, a.getTitle());
        return a;
    }

    /**
     * 撤回公告：删除发布存档，并按 announcement_id 清理已扇出到各用户铃铛的通知。
     *
     * @return 清理掉的通知条数（前端提示用）
     */
    public int recall(Long id) {
        Announcement a = announcementMapper.selectById(id);
        if (a == null) {
            throw new BizException("公告不存在或已撤回");
        }
        int cleaned = notificationCleanup(id);
        announcementMapper.deleteById(id);
        log.info("公告已撤回 id={} 清理 {} 条通知: {}", id, cleaned, a.getTitle());
        return cleaned;
    }

    private int notificationCleanup(Long announcementId) {
        List<Notification> stale = notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getType, NOTIFY_TYPE)
                .eq(Notification::getAnnouncementId, announcementId));
        for (Notification n : stale) {
            notificationMapper.deleteById(n.getId());
        }
        return stale.size();
    }

    /** 发布历史（管理页展示），最新在前。 */
    public List<Announcement> history() {
        return announcementMapper.selectList(new LambdaQueryWrapper<Announcement>()
                .orderByDesc(Announcement::getId)
                .last("LIMIT 20"));
    }
}
