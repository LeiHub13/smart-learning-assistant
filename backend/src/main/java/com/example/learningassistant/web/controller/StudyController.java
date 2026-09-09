package com.example.learningassistant.web.controller;

import com.example.learningassistant.common.ApiResponse;
import com.example.learningassistant.security.AuthUser;
import com.example.learningassistant.security.CurrentUser;
import com.example.learningassistant.study.service.StudyService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 学习时长接口：心跳上报 + 汇总（总时长/热力图/分课程）。
 */
@RestController
@RequestMapping("/api/study")
@RequiredArgsConstructor
public class StudyController {

    private final StudyService studyService;

    @PostMapping("/heartbeat")
    public ApiResponse<Void> heartbeat(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        AuthUser u = CurrentUser.get(request);
        Long courseId = body.get("courseId") == null ? null
                : Long.valueOf(String.valueOf(body.get("courseId")));
        int minutes = body.get("minutes") == null ? 0 : (int) Double.parseDouble(String.valueOf(body.get("minutes")));
        studyService.heartbeat(u.id(), courseId, minutes);
        return ApiResponse.ok(null);
    }

    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary(HttpServletRequest request) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(studyService.summary(u.id()));
    }
}
