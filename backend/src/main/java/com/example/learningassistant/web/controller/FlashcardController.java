package com.example.learningassistant.web.controller;

import com.example.learningassistant.common.ApiResponse;
import com.example.learningassistant.flashcard.entity.Flashcard;
import com.example.learningassistant.flashcard.service.FlashcardService;
import com.example.learningassistant.security.AuthUser;
import com.example.learningassistant.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 闪卡接口：三入口生成、学习队列、自评与 Anki 导出。资源按 userId 隔离。
 */
@RestController
@RequestMapping("/api/flashcards")
@RequiredArgsConstructor
public class FlashcardController {

    private final FlashcardService flashcardService;

    /** 生成闪卡：mode=chat（答疑会话提炼）/ doc（文档提炼）/ mistake（错题直转）。 */
    @PostMapping("/generate")
    public ApiResponse<Map<String, Object>> generate(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        AuthUser u = CurrentUser.get(request);
        String mode = String.valueOf(body.getOrDefault("mode", ""));
        Long sourceId = body.get("sourceId") == null ? null : Long.valueOf(String.valueOf(body.get("sourceId")));
        int created = switch (mode) {
            case "chat" -> flashcardService.generateFromChat(u.id(), sourceId);
            case "doc" -> flashcardService.generateFromDoc(u.id(), sourceId);
            case "mistake" -> flashcardService.generateFromMistakes(u.id());
            default -> throw new com.example.learningassistant.common.BizException("不支持的闪卡生成方式");
        };
        return ApiResponse.ok(Map.of("created", created, "mode", mode));
    }

    /** 学习队列：薄弱盒与久未复习的卡优先。 */
    @GetMapping("/study")
    public ApiResponse<List<Flashcard>> study(HttpServletRequest request,
                                              @RequestParam(required = false) Long courseId,
                                              @RequestParam(defaultValue = "10") int count) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(flashcardService.study(u.id(), courseId, count));
    }

    /** 翻卡自评：result=ok（记住，升盒）/ miss（没记住，回盒 1）。 */
    @PostMapping("/{id}/review")
    public ApiResponse<Flashcard> review(HttpServletRequest request, @PathVariable Long id,
                                         @RequestBody Map<String, String> body) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(flashcardService.review(u.id(), id, body.get("result")));
    }

    /** 闪卡总数。 */
    @GetMapping("/count")
    public ApiResponse<Long> count(HttpServletRequest request) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(flashcardService.count(u.id()));
    }

    /** 今日到期卡数（SRS 复习义务），供首页与闪卡页展示。 */
    @GetMapping("/due")
    public ApiResponse<Map<String, Object>> due(HttpServletRequest request,
                                                @RequestParam(required = false) Long courseId) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(Map.of(
                "due", flashcardService.dueCount(u.id(), courseId),
                "total", flashcardService.count(u.id())));
    }

    /** Anki 导出：制表符分隔纯文本，登录用户自己的全部卡片。 */
    @GetMapping("/export")
    public void export(HttpServletRequest request, HttpServletResponse response) throws IOException {
        AuthUser u = CurrentUser.get(request);
        String text = flashcardService.exportText(u.id());
        String name = URLEncoder.encode("flashcards-anki.txt", StandardCharsets.UTF_8);
        response.setContentType("text/plain;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + name);
        response.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
    }

    /** 删除单张卡片。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(HttpServletRequest request, @PathVariable Long id) {
        AuthUser u = CurrentUser.get(request);
        flashcardService.delete(u.id(), id);
        return ApiResponse.ok(null);
    }
}
