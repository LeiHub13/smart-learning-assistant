package com.example.learningassistant.web.controller;

import com.example.learningassistant.common.ApiResponse;
import com.example.learningassistant.practice.service.MistakeService;
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
 * 错题本接口：最近一次作答仍错误的题目归集，支持错题重练。
 */
@RestController
@RequestMapping("/api/mistakes")
@RequiredArgsConstructor
public class MistakeController {

    private final MistakeService mistakeService;

    @GetMapping
    public ApiResponse<Map<String, Object>> page(HttpServletRequest request,
                                                 @RequestParam(required = false) Long courseId,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "10") int size) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(mistakeService.page(u.id(), courseId, page, size));
    }
}
