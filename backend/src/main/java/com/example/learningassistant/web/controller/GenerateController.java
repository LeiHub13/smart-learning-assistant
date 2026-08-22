package com.example.learningassistant.web.controller;

import com.example.learningassistant.common.ApiResponse;
import com.example.learningassistant.generate.entity.GeneratedContent;
import com.example.learningassistant.generate.service.GeneratorService;
import com.example.learningassistant.practice.entity.Question;
import com.example.learningassistant.security.AuthUser;
import com.example.learningassistant.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * AI 内容生成接口：讲义 / 练习题 / 生成历史。
 */
@Slf4j
@RestController
@RequestMapping("/api/generate")
@RequiredArgsConstructor
public class GenerateController {

    private final GeneratorService generatorService;

    @PostMapping("/lecture/stream")
    public SseEmitter lectureStream(HttpServletRequest request, @RequestBody Map<String, String> body) {
        AuthUser u = CurrentUser.get(request);
        Long courseId = body.get("courseId") == null ? null : Long.valueOf(body.get("courseId"));
        String topic = body.get("topic");
        String kp = body.get("kp");
        SseEmitter emitter = new SseEmitter(300_000L);
        generatorService.streamLecture(u.id(), courseId, topic, kp,
                delta -> safeSend(emitter, Map.of("delta", delta)),
                emitter::complete,
                e -> {
                    log.warn("讲义流式生成失败: {}", e.getMessage());
                    emitter.completeWithError(e);
                });
        return emitter;
    }

    private void safeSend(SseEmitter emitter, Object data) {
        try {
            emitter.send(SseEmitter.event().data(data));
        } catch (IOException e) {
            log.warn("SSE send 失败: {}", e.getMessage());
            emitter.completeWithError(e);
        }
    }

    @PostMapping("/lecture")
    public ApiResponse<GeneratedContent> lecture(HttpServletRequest request, @RequestBody Map<String, String> body) {
        AuthUser u = CurrentUser.get(request);
        Long courseId = body.get("courseId") == null ? null : Long.valueOf(body.get("courseId"));
        return ApiResponse.ok(generatorService.generateLecture(u.id(), courseId, body.get("topic"), body.get("kp")));
    }

    @PostMapping("/questions")
    public ApiResponse<List<Question>> questions(HttpServletRequest request, @RequestBody Map<String, String> body) {
        AuthUser u = CurrentUser.get(request);
        Long courseId = body.get("courseId") == null ? null : Long.valueOf(body.get("courseId"));
        int count = body.get("count") == null ? 5 : Integer.parseInt(body.get("count"));
        return ApiResponse.ok(generatorService.generateQuestions(u.id(), courseId, body.get("kp"), count));
    }

    @GetMapping("/history")
    public ApiResponse<List<GeneratedContent>> history(HttpServletRequest request) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(generatorService.history(u.id()));
    }
}
