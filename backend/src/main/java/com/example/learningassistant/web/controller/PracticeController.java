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
    public ApiResponse<List<Map<String, Object>>> paper(HttpServletRequest request,
                                                        @RequestParam Long courseId,
                                                        @RequestParam(defaultValue = "5") int count,
                                                        @RequestParam(defaultValue = "false") boolean favorite,
                                                        @RequestParam(defaultValue = "false") boolean mistake,
                                                        @RequestParam(required = false) String kp,
                                                        @RequestParam(required = false) String ids) {
        AuthUser u = CurrentUser.get(request);
        // 选题练习模式：ids 优先，按所选顺序组卷（忽略数量/知识点/错题等抽题规则）
        if (ids != null && !ids.isBlank()) {
            List<Long> idList = java.util.Arrays.stream(ids.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .map(Long::valueOf).toList();
            return ApiResponse.ok(practiceService.paperByIds(u.id(), courseId, idList));
        }
        return ApiResponse.ok(practiceService.paper(u.id(), courseId, count, favorite, mistake, kp));
    }

    /** 选题练习的课程题目清单（不含答案与解析）。 */
    @GetMapping("/course-questions")
    public ApiResponse<List<Map<String, Object>>> courseQuestions(HttpServletRequest request,
                                                                  @RequestParam Long courseId) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(practiceService.courseQuestions(courseId));
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
    public ApiResponse<Map<String, Object>> history(HttpServletRequest request,
                                                    @RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "5") int size) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(practiceService.history(u.id(), page, size));
    }

    @GetMapping("/trend")
    public ApiResponse<List<Map<String, Object>>> trend(HttpServletRequest request,
                                                        @RequestParam(required = false) Long courseId) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(practiceService.trend(u.id(), courseId));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> report(HttpServletRequest request, @PathVariable Long id) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(practiceService.report(u.id(), id));
    }
}
