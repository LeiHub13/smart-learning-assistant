package com.example.learningassistant.web.controller;

import com.example.learningassistant.export.service.ExportService;
import com.example.learningassistant.security.AuthUser;
import com.example.learningassistant.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 数据导出接口：练习记录 / 考试成绩 → Excel 下载（前端经 blob + 认证头拉取）。
 */
@RestController
@RequestMapping("/api/export")
@RequiredArgsConstructor
public class ExportController {

    private static final String XLSX_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ExportService exportService;

    @GetMapping("/practice.xlsx")
    public void practice(HttpServletRequest request, HttpServletResponse response) throws Exception {
        AuthUser u = CurrentUser.get(request);
        byte[] data = exportService.practiceWorkbook(u.id());
        headers(response, "练习记录.xlsx");
        response.getOutputStream().write(data);
    }

    @GetMapping("/exams.xlsx")
    public void exams(HttpServletRequest request, HttpServletResponse response) throws Exception {
        AuthUser u = CurrentUser.get(request);
        byte[] data = exportService.examsWorkbook(u.id());
        headers(response, "考试成绩.xlsx");
        response.getOutputStream().write(data);
    }

    private void headers(HttpServletResponse response, String name) {
        response.setContentType(XLSX_TYPE);
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''"
                + URLEncoder.encode(name, StandardCharsets.UTF_8));
    }
}
