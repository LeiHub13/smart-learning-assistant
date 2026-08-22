package com.example.learningassistant.web.controller;

import com.example.learningassistant.common.ApiResponse;
import com.example.learningassistant.report.entity.Report;
import com.example.learningassistant.report.service.ReportService;
import com.example.learningassistant.security.AuthUser;
import com.example.learningassistant.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 学习报告接口：生成周报 / 列表 / 详情 / PDF 导出。
 */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @PostMapping("/weekly")
    public ApiResponse<Report> generateWeekly(HttpServletRequest request,
                                              @RequestParam(defaultValue = "1") Long courseId) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(reportService.generateWeekly(u.id(), courseId));
    }

    @GetMapping
    public ApiResponse<List<Report>> list(HttpServletRequest request) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(reportService.list(u.id()));
    }

    @GetMapping("/{id}")
    public ApiResponse<Report> detail(HttpServletRequest request, @PathVariable Long id) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(reportService.detail(u.id(), id));
    }

    @GetMapping("/{id}/pdf")
    public void exportPdf(HttpServletRequest request, @PathVariable Long id,
                          HttpServletResponse response) throws IOException {
        AuthUser u = CurrentUser.get(request);
        byte[] pdf = reportService.exportPdf(u.id(), id);
        String name = URLEncoder.encode("学习报告-" + id + ".pdf", StandardCharsets.UTF_8);
        response.setContentType("application/pdf");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + name);
        response.getOutputStream().write(pdf);
    }
}
