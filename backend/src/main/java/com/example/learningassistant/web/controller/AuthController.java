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
import org.springframework.web.bind.annotation.RequestParam;
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
    private final com.example.learningassistant.user.service.AvatarService avatarService;

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
                "email", user.getEmail() == null ? "" : user.getEmail(),
                "avatar", user.getAvatar() == null ? "" : user.getAvatar()));
    }

    @PutMapping("/me/email")
    public ApiResponse<Void> bindEmail(HttpServletRequest request, @RequestBody Map<String, String> body) {
        AuthUser u = CurrentUser.get(request);
        authService.bindEmail(u.id(), body.get("email"));
        return ApiResponse.ok(null);
    }

    @PutMapping("/me/profile")
    public ApiResponse<Void> updateProfile(HttpServletRequest request, @RequestBody Map<String, String> body) {
        AuthUser u = CurrentUser.get(request);
        authService.updateNickname(u.id(), body.get("nickname"));
        return ApiResponse.ok(null);
    }

    @PutMapping("/me/password")
    public ApiResponse<Void> changePassword(HttpServletRequest request, @RequestBody Map<String, String> body) {
        AuthUser u = CurrentUser.get(request);
        authService.changePassword(u.id(), body.get("oldPassword"), body.get("newPassword"));
        return ApiResponse.ok(null);
    }

    @PostMapping(value = "/me/avatar", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, String>> uploadAvatar(HttpServletRequest request,
                                                         @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(Map.of("avatar", avatarService.upload(u.id(), file)));
    }
}
