package com.example.learningassistant.web.controller;

import com.example.learningassistant.common.ApiResponse;
import com.example.learningassistant.favorite.service.FavoriteService;
import com.example.learningassistant.security.AuthUser;
import com.example.learningassistant.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 收藏夹接口：收藏/取消切换、收藏夹概览、数量。
 */
@RestController
@RequestMapping("/api/favorites")
@RequiredArgsConstructor
public class FavoriteController {

    private final FavoriteService favoriteService;

    /** 切换收藏：{questionId} → {favorited: true/false} */
    @PostMapping("/toggle")
    public ApiResponse<Map<String, Object>> toggle(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        AuthUser u = CurrentUser.get(request);
        Long questionId = Long.valueOf(String.valueOf(body.get("questionId")));
        boolean favorited = favoriteService.toggle(u.id(), questionId);
        return ApiResponse.ok(Map.of("favorited", favorited));
    }

    /** 收藏夹概览（courseId 可选过滤，不含答案）。 */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(HttpServletRequest request,
                                                       @RequestParam(required = false) Long courseId) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(favoriteService.list(u.id(), courseId));
    }

    /** 收藏数量：count=当前课程收藏数，total=全部课程收藏数。 */
    @GetMapping("/count")
    public ApiResponse<Map<String, Object>> count(HttpServletRequest request,
                                                  @RequestParam(required = false) Long courseId) {
        AuthUser u = CurrentUser.get(request);
        Map<String, Long> c = favoriteService.counts(u.id(), courseId);
        return ApiResponse.ok(new java.util.LinkedHashMap<>(c));
    }
}
