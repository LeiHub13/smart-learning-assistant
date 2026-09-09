package com.example.learningassistant.web.controller;

import com.example.learningassistant.common.ApiResponse;
import com.example.learningassistant.security.AuthUser;
import com.example.learningassistant.security.CurrentUser;
import com.example.learningassistant.user.entity.User;
import com.example.learningassistant.user.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证接口：登录 / 注册 / 当前用户（JWT 双 Token）。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ApiResponse<Map<String, String>> login(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(authService.login(body.get("username"), body.get("password")));
    }

    @PostMapping("/register")
    public ApiResponse<Map<String, String>> register(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(authService.register(
                body.get("username"), body.get("password"), body.get("nickname")));
    }

    @GetMapping("/me")
    public ApiResponse<Map<String, String>> me(HttpServletRequest request) {
        AuthUser u = CurrentUser.get(request);
        User user = authService.me(u.id());
        return ApiResponse.ok(Map.of(
                "id", String.valueOf(user.getId()),
                "username", user.getUsername(),
                "nickname", user.getNickname() == null ? "" : user.getNickname(),
                "email", user.getEmail() == null ? "" : user.getEmail()));
    }

    @PutMapping("/me/email")
    public ApiResponse<Void> bindEmail(HttpServletRequest request, @RequestBody Map<String, String> body) {
        AuthUser u = CurrentUser.get(request);
        authService.bindEmail(u.id(), body.get("email"));
        return ApiResponse.ok(null);
    }
}
