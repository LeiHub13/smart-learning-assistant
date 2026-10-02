package com.example.learningassistant.web.controller;

import com.example.learningassistant.common.ApiResponse;
import com.example.learningassistant.generate.service.GeneratorService;
import com.example.learningassistant.practice.entity.Question;
import com.example.learningassistant.practice.service.MistakeService;
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
 * 错题本接口：最近一次作答仍错误的题目归集，支持错题重练与错题变式。
 */
@RestController
@RequestMapping("/api/mistakes")
@RequiredArgsConstructor
public class MistakeController {

    private final MistakeService mistakeService;
    private final GeneratorService generatorService;

    @GetMapping
    public ApiResponse<Map<String, Object>> page(HttpServletRequest request,
                                                 @RequestParam(required = false) Long courseId,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "10") int size) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(mistakeService.page(u.id(), courseId, page, size));
    }

    /** 在册题目 id 集（作答推导 + 手动收录合并，含兼容的空课程行），供题库/收藏夹标注「已收录」。 */
    @GetMapping("/collected")
    public ApiResponse<List<Long>> collected(HttpServletRequest request,
                                             @RequestParam(required = false) Long courseId) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(mistakeService.mistakeIds(u.id(), courseId));
    }

    /**
     * 手动收录错题：把题库中的题加入错题本（与 Agent 收录同链路），收录后重练答对自动出本。
     * 重复收录幂等（刷新收录时间重新起算）。
     */
    @PostMapping("/{questionId}/collect")
    public ApiResponse<Map<String, Object>> collect(HttpServletRequest request,
                                                    @PathVariable Long questionId) {
        AuthUser u = CurrentUser.get(request);
        mistakeService.addManual(u.id(), null, questionId);
        return ApiResponse.ok(Map.of("collected", true));
    }

    /**
     * 错题变式（举一反三）：以该错题为参照生成变式题，返回与练习答题页同构的题目投影（不含答案）。
     * 变式题入库 t_question（source=AI），作答后走 /api/practice/submit 统一判分与掌握度更新。
     */
    @PostMapping("/{questionId}/variants")
    public ApiResponse<List<Map<String, Object>>> variants(HttpServletRequest request,
                                                           @PathVariable Long questionId,
                                                           @RequestBody(required = false) Map<String, Object> body) {
        AuthUser u = CurrentUser.get(request);
        int count = 2;
        if (body != null && body.get("count") != null) {
            try {
                count = Integer.parseInt(String.valueOf(body.get("count")));
            } catch (NumberFormatException ignored) {
            }
        }
        count = Math.min(Math.max(count, 1), 4);
        List<Question> qs = generatorService.generateVariants(u.id(), questionId, count);
        return ApiResponse.ok(qs.stream().map(q -> PracticeService.toPaperItem(q, false)).toList());
    }
}
