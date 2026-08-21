package com.example.learningassistant.web.controller;

import com.example.learningassistant.common.ApiResponse;
import com.example.learningassistant.practice.entity.Practice;
import com.example.learningassistant.practice.service.PracticeService;
import com.example.learningassistant.security.AuthUser;
import com.example.learningassistant.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 练习接口：抽题、提交判分、历史、报告。
 */
@RestController
@RequestMapping("/api/practice")
@RequiredArgsConstructor
public class PracticeController {

    private final PracticeService practiceService;

    @GetMapping("/paper")
    public ApiResponse<List<Map<String, Object>>> paper(@RequestParam Long courseId,
                                                        @RequestParam(defaultValue = "5") int count) {
        return ApiResponse.ok(practiceService.paper(courseId, count));
    }

    @PostMapping("/submit")
    public ApiResponse<Practice> submit(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        AuthUser u = CurrentUser.get(request);
        Long courseId = Long.valueOf(String.valueOf(body.get("courseId")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        return ApiResponse.ok(practiceService.submit(u.id(), courseId, items));
    }

    @GetMapping("/history")
    public ApiResponse<List<Practice>> history(HttpServletRequest request) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(practiceService.history(u.id()));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> report(@PathVariable Long id) {
        return ApiResponse.ok(practiceService.report(id));
    }
}
