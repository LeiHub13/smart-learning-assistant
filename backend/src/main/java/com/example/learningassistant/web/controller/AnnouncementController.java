package com.example.learningassistant.web.controller;

import com.example.learningassistant.admin.service.AdminService;
import com.example.learningassistant.common.ApiResponse;
import com.example.learningassistant.common.BizException;
import com.example.learningassistant.notify.entity.Announcement;
import com.example.learningassistant.notify.service.AnnouncementService;
import com.example.learningassistant.security.AuthUser;
import com.example.learningassistant.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 系统公告接口：发布与历史仅限管理白名单用户；普通用户经由铃铛通知接收公告。
 */
@RestController
@RequestMapping("/api/announcements")
@RequiredArgsConstructor
public class AnnouncementController {

    private final AnnouncementService announcementService;
    private final AdminService adminService;

    @PostMapping
    public ApiResponse<Announcement> publish(HttpServletRequest request, @RequestBody Map<String, String> body) {
        AuthUser me = requireAdmin(request);
        return ApiResponse.ok(announcementService.publish(me.id(), display(me),
                body.get("title"), body.get("content")));
    }

    @GetMapping
    public ApiResponse<List<Announcement>> history(HttpServletRequest request) {
        requireAdmin(request);
        return ApiResponse.ok(announcementService.history());
    }

    /** 撤回：删除存档并清理已扇出到各用户铃铛的通知，返回清理条数。 */
    @org.springframework.web.bind.annotation.DeleteMapping("/{id}")
    public ApiResponse<Map<String, Integer>> recall(HttpServletRequest request, @org.springframework.web.bind.annotation.PathVariable Long id) {
        requireAdmin(request);
        return ApiResponse.ok(Map.of("recalled", announcementService.recall(id)));
    }

    private AuthUser requireAdmin(HttpServletRequest request) {
        AuthUser me = CurrentUser.get(request);
        if (!adminService.isAdmin(me.username())) {
            throw new BizException("仅管理员可管理系统公告");
        }
        return me;
    }

    private static String display(AuthUser u) {
        return u.nickname() == null || u.nickname().isBlank() ? u.username() : u.nickname();
    }
}
