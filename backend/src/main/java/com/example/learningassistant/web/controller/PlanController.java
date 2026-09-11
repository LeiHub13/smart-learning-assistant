package com.example.learningassistant.web.controller;

import com.example.learningassistant.common.ApiResponse;
import com.example.learningassistant.plan.entity.PlanTask;
import com.example.learningassistant.plan.entity.StudyPlan;
import com.example.learningassistant.plan.service.PlanService;
import com.example.learningassistant.security.AuthUser;
import com.example.learningassistant.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 学习计划接口：生成 / 列表 / 详情 / 打卡 / 删除。
 */
@RestController
@RequestMapping("/api/plans")
@RequiredArgsConstructor
public class PlanController {

    private final PlanService planService;

    @PostMapping
    public ApiResponse<StudyPlan> generate(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        AuthUser u = CurrentUser.get(request);
        Long courseId = body.get("courseId") == null ? 1L : Long.valueOf(String.valueOf(body.get("courseId")));
        int days = body.get("days") == null ? 7 : Integer.parseInt(String.valueOf(body.get("days")));
        return ApiResponse.ok(planService.generate(u.id(), courseId, String.valueOf(body.get("goal")), days));
    }

    @GetMapping
    public ApiResponse<List<StudyPlan>> list(HttpServletRequest request) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(planService.list(u.id()));
    }

    /** 打卡日历：month=yyyy-MM，按天聚合任务/完成数。 */
    @GetMapping("/calendar")
    public ApiResponse<List<Map<String, Object>>> calendar(HttpServletRequest request,
                                                           @RequestParam String month) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(planService.calendar(u.id(), month));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(HttpServletRequest request, @PathVariable Long id) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(planService.detail(u.id(), id));
    }

    @PostMapping("/tasks/{taskId}/checkin")
    public ApiResponse<PlanTask> checkIn(HttpServletRequest request, @PathVariable Long taskId) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(planService.checkIn(u.id(), taskId));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(HttpServletRequest request, @PathVariable Long id) {
        AuthUser u = CurrentUser.get(request);
        planService.delete(u.id(), id);
        return ApiResponse.ok(null);
    }
}
