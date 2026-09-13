package com.example.learningassistant.web.controller;

import com.example.learningassistant.common.ApiResponse;
import com.example.learningassistant.practice.entity.Question;
import com.example.learningassistant.practice.service.QuestionService;
import com.example.learningassistant.security.AuthUser;
import com.example.learningassistant.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 题库管理接口：分页查询、手动录题、编辑、删除（课程成员可管理）。
 */
@RestController
@RequestMapping("/api/questions")
@RequiredArgsConstructor
public class QuestionController {

    private final QuestionService questionService;

    @GetMapping
    public ApiResponse<Map<String, Object>> page(HttpServletRequest request,
                                                 @RequestParam Long courseId,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "10") int size,
                                                 @RequestParam(required = false) String type,
                                                 @RequestParam(required = false) String difficulty,
                                                 @RequestParam(required = false) String keyword) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(questionService.page(u.id(), courseId, page, size, type, difficulty, keyword));
    }

    @PostMapping
    public ApiResponse<Question> create(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        AuthUser u = CurrentUser.get(request);
        Long courseId = Long.valueOf(String.valueOf(body.get("courseId")));
        return ApiResponse.ok(questionService.create(u.id(), courseId,
                str(body, "type"), str(body, "stem"), str(body, "options"),
                str(body, "answer"), str(body, "analysis"), str(body, "kpName"), str(body, "difficulty")));
    }

    @PutMapping("/{id}")
    public ApiResponse<Question> update(HttpServletRequest request, @PathVariable Long id,
                                        @RequestBody Map<String, Object> body) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(questionService.update(u.id(), id,
                str(body, "type"), str(body, "stem"), str(body, "options"),
                str(body, "answer"), str(body, "analysis"), str(body, "kpName"), str(body, "difficulty")));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(HttpServletRequest request, @PathVariable Long id) {
        AuthUser u = CurrentUser.get(request);
        questionService.delete(u.id(), id);
        return ApiResponse.ok(null);
    }

    private String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
