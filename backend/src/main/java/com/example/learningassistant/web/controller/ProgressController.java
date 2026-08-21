package com.example.learningassistant.web.controller;

import com.example.learningassistant.common.ApiResponse;
import com.example.learningassistant.progress.service.ProgressService;
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
 * 学情分析接口：掌握度 / 错题本 / AI 复习建议。
 */
@RestController
@RequestMapping("/api/progress")
@RequiredArgsConstructor
public class ProgressController {

    private final ProgressService progressService;

    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary(HttpServletRequest request,
                                                    @RequestParam(required = false) Long courseId) {
        AuthUser u = CurrentUser.get(request);
        if (courseId == null) {
            courseId = 1L;
        }
        return ApiResponse.ok(progressService.summary(u.id(), courseId));
    }
}
