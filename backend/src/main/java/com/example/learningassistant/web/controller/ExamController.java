package com.example.learningassistant.web.controller;

import com.example.learningassistant.common.ApiResponse;
import com.example.learningassistant.exam.entity.Exam;
import com.example.learningassistant.exam.entity.ExamRecord;
import com.example.learningassistant.exam.service.ExamService;
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
 * 考试接口：组卷（手动/随机）、列表、详情、开考、交卷、成绩单。
 */
@RestController
@RequestMapping("/api/exams")
@RequiredArgsConstructor
public class ExamController {

    private final ExamService examService;

    /** 手动组卷：{courseId, title, durationMin?, questionIds[]} */
    @PostMapping
    public ApiResponse<Exam> compose(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        AuthUser u = CurrentUser.get(request);
        Long courseId = Long.valueOf(String.valueOf(body.get("courseId")));
        String title = String.valueOf(body.get("title"));
        Integer durationMin = body.get("durationMin") == null ? null
                : (int) Double.parseDouble(String.valueOf(body.get("durationMin")));
        @SuppressWarnings("unchecked")
        List<Long> questionIds = ((List<Object>) body.get("questionIds")).stream()
                .map(v -> Long.valueOf(String.valueOf(v))).toList();
        return ApiResponse.ok(examService.compose(u.id(), courseId, title, durationMin, questionIds));
    }

    /** 随机组卷：{courseId, title, durationMin?, count} */
    @PostMapping("/auto")
    public ApiResponse<Exam> composeAuto(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        AuthUser u = CurrentUser.get(request);
        Long courseId = Long.valueOf(String.valueOf(body.get("courseId")));
        String title = String.valueOf(body.get("title"));
        Integer durationMin = body.get("durationMin") == null ? null
                : (int) Double.parseDouble(String.valueOf(body.get("durationMin")));
        int count = (int) Double.parseDouble(String.valueOf(body.get("count")));
        return ApiResponse.ok(examService.composeAuto(u.id(), courseId, title, durationMin, count));
    }

    /** 考试列表（courseId 可选，含我的最好成绩）。 */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(HttpServletRequest request,
                                                       @RequestParam(required = false) Long courseId) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(examService.list(courseId, u.id()));
    }

    /** 题库浏览（组卷选题用，不含答案）。 */
    @GetMapping("/bank")
    public ApiResponse<List<Map<String, Object>>> questionBank(@RequestParam Long courseId) {
        return ApiResponse.ok(examService.questionBank(courseId));
    }

    /** 考试详情（题目不含答案）。 */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(examService.detail(id));
    }

    /** 开考/恢复作答。 */
    @PostMapping("/{id}/start")
    public ApiResponse<ExamRecord> start(HttpServletRequest request, @PathVariable Long id) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(examService.start(u.id(), id));
    }

    /** 交卷：{items: [{questionId, answer}]} */
    @PostMapping("/records/{recordId}/submit")
    public ApiResponse<Map<String, Object>> submit(HttpServletRequest request, @PathVariable Long recordId,
                                                   @RequestBody Map<String, Object> body) {
        AuthUser u = CurrentUser.get(request);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        return ApiResponse.ok(examService.submit(u.id(), recordId, items));
    }

    /** 成绩单。 */
    @GetMapping("/records/{recordId}/report")
    public ApiResponse<Map<String, Object>> report(HttpServletRequest request, @PathVariable Long recordId) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(examService.report(u.id(), recordId));
    }

    /** 我在该考试的历次成绩。 */
    @GetMapping("/{id}/my-records")
    public ApiResponse<List<ExamRecord>> myRecords(HttpServletRequest request, @PathVariable Long id) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(examService.myRecords(u.id(), id));
    }
}
