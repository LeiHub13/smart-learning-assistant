package com.example.learningassistant.web.controller;

import com.example.learningassistant.common.ApiResponse;
import com.example.learningassistant.hall.service.FriendService;
import com.example.learningassistant.security.AuthUser;
import com.example.learningassistant.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 好友：请求（按用户名）/ 同意 / 拒绝 / 删除 / 列表。
 */
@RestController
@RequestMapping("/api/friends")
@RequiredArgsConstructor
public class FriendsController {

    private final FriendService friendService;

    @GetMapping
    public ApiResponse<Map<String, Object>> list(HttpServletRequest request) {
        AuthUser me = CurrentUser.get(request);
        return ApiResponse.ok(friendService.list(me.id()));
    }

    @PostMapping("/request")
    public ApiResponse<Void> request(HttpServletRequest request, @RequestBody Map<String, String> body) {
        AuthUser me = CurrentUser.get(request);
        friendService.request(me.id(), body.get("username"));
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/accept")
    public ApiResponse<Void> accept(HttpServletRequest request, @PathVariable Long id) {
        AuthUser me = CurrentUser.get(request);
        friendService.accept(me.id(), id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<Void> reject(HttpServletRequest request, @PathVariable Long id) {
        AuthUser me = CurrentUser.get(request);
        friendService.reject(me.id(), id);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> remove(HttpServletRequest request, @PathVariable Long id) {
        AuthUser me = CurrentUser.get(request);
        friendService.remove(me.id(), id);
        return ApiResponse.ok(null);
    }
}
