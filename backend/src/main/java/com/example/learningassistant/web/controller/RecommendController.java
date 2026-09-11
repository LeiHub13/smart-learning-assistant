package com.example.learningassistant.web.controller;

import com.example.learningassistant.common.ApiResponse;
import com.example.learningassistant.recommend.service.RecommendService;
import com.example.learningassistant.security.AuthUser;
import com.example.learningassistant.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 个性化推荐接口：按课程返回今日推荐（专项练习/待复习/相关资料）。
 */
@RestController
@RequestMapping("/api/recommend")
@RequiredArgsConstructor
public class RecommendController {

    private final RecommendService recommendService;

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> recommend(HttpServletRequest request,
                                                            @RequestParam Long courseId) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(recommendService.recommend(u.id(), courseId));
    }
}
