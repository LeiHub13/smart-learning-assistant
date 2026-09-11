package com.example.learningassistant.web.controller;

import com.example.learningassistant.common.ApiResponse;
import com.example.learningassistant.notify.entity.MailLog;
import com.example.learningassistant.notify.entity.Notification;
import com.example.learningassistant.notify.mapper.MailLogMapper;
import com.example.learningassistant.notify.service.NotifyService;
import com.example.learningassistant.security.AuthUser;
import com.example.learningassistant.security.CurrentUser;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 通知接口：站内系统通知（列表/未读/已读）+ 邮件发送记录。
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotifyService notifyService;
    private final MailLogMapper mailLogMapper;

    @GetMapping
    public ApiResponse<List<Notification>> list(HttpServletRequest request) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(notifyService.list(u.id()));
    }

    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Long>> unreadCount(HttpServletRequest request) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(Map.of("count", notifyService.unreadCount(u.id())));
    }

    @PostMapping("/{id}/read")
    public ApiResponse<Void> markRead(HttpServletRequest request, @PathVariable Long id) {
        AuthUser u = CurrentUser.get(request);
        notifyService.markRead(u.id(), id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/read-all")
    public ApiResponse<Void> markAllRead(HttpServletRequest request) {
        AuthUser u = CurrentUser.get(request);
        notifyService.markAllRead(u.id());
        return ApiResponse.ok(null);
    }

    /** 邮件通知记录（最近 50 封，成败留痕）。 */
    @GetMapping("/mails")
    public ApiResponse<List<MailLog>> mails(HttpServletRequest request) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(mailLogMapper.selectList(new LambdaQueryWrapper<MailLog>()
                .eq(MailLog::getUserId, u.id())
                .orderByDesc(MailLog::getCreatedAt)
                .last("LIMIT 50")));
    }
}
