package com.example.learningassistant.web.controller;

import com.example.learningassistant.common.ApiResponse;
import com.example.learningassistant.search.service.SearchService;
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
 * 全局搜索接口：q=关键词，courseId 可选（限定课程范围）。
 */
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    @GetMapping
    public ApiResponse<Map<String, Object>> search(HttpServletRequest request,
                                                   @RequestParam String q,
                                                   @RequestParam(required = false) Long courseId) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(searchService.search(u.id(), q, courseId));
    }
}
