package com.example.learningassistant.web.controller;

import com.example.learningassistant.admin.service.AdminService;
import com.example.learningassistant.common.ApiResponse;
import com.example.learningassistant.common.BizException;
import com.example.learningassistant.security.AuthUser;
import com.example.learningassistant.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理看板接口：仅 app.admin.usernames 白名单内的用户可访问（服务层二次校验）。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    @GetMapping("/dashboard")
    public ApiResponse<Map<String, Object>> dashboard(HttpServletRequest request) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(adminService.dashboard(u.username()));
    }

    /** 用户列表：关键字匹配用户名/昵称/邮箱，附带练习次数与最近学习日期。 */
    @GetMapping("/users")
    public ApiResponse<Map<String, Object>> users(HttpServletRequest request,
                                                  @RequestParam(required = false) String keyword,
                                                  @RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "10") int size) {
        AuthUser u = CurrentUser.get(request);
        if (!adminService.isAdmin(u.username())) {
            throw new BizException("无权访问管理看板");
        }
        return ApiResponse.ok(adminService.users(keyword, page, size));
    }
}
