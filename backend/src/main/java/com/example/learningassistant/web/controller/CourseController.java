package com.example.learningassistant.web.controller;

import com.example.learningassistant.common.ApiResponse;
import com.example.learningassistant.course.entity.Course;
import com.example.learningassistant.course.service.CourseService;
import com.example.learningassistant.security.AuthUser;
import com.example.learningassistant.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 课程接口：课程列表（含选课状态/统计）、创建课程、选课。
 */
@RestController
@RequestMapping("/api/courses")
@RequiredArgsConstructor
public class CourseController {

    private final CourseService courseService;

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(HttpServletRequest request) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(courseService.listFor(u.id()));
    }

    @PostMapping
    public ApiResponse<Course> create(HttpServletRequest request, @RequestBody Map<String, String> body) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(courseService.create(
                body.get("name"), body.get("description"), u.id(), u.nickname()));
    }

    @PostMapping("/{id}/enroll")
    public ApiResponse<Void> enroll(HttpServletRequest request, @PathVariable Long id) {
        AuthUser u = CurrentUser.get(request);
        courseService.enroll(u.id(), id);
        return ApiResponse.ok(null);
    }

    /** 删除课程（仅创建者可删，级联清理依赖数据）。 */
    @org.springframework.web.bind.annotation.DeleteMapping("/{id}")
    public ApiResponse<Void> delete(HttpServletRequest request, @PathVariable Long id) {
        AuthUser u = CurrentUser.get(request);
        courseService.delete(u.id(), id);
        return ApiResponse.ok(null);
    }
}
