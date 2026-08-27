package com.example.learningassistant.web.controller;

import com.example.learningassistant.chat.entity.ChatMessage;
import com.example.learningassistant.chat.entity.ChatSession;
import com.example.learningassistant.chat.service.ChatService;
import com.example.learningassistant.common.ApiResponse;
import com.example.learningassistant.security.AuthUser;
import com.example.learningassistant.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 答疑接口：会话管理 + SSE 流式对话。
 */
@RestController
@RequestMapping("/api/chat/sessions")
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private final ChatService chatService;

    @GetMapping
    public ApiResponse<List<ChatSession>> sessions(HttpServletRequest request) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(chatService.sessions(u.id()));
    }

    @PostMapping
    public ApiResponse<ChatSession> create(HttpServletRequest request, @RequestBody Map<String, Long> body) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(chatService.createSession(u.id(), body.get("courseId"), body.get("kbId")));
    }

    @GetMapping("/{id}/messages")
    public ApiResponse<List<ChatMessage>> messages(HttpServletRequest request, @PathVariable Long id) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(chatService.messages(id, u.id()));
    }

    @PutMapping("/{id}")
    public ApiResponse<ChatSession> rename(HttpServletRequest request, @PathVariable Long id,
                                           @RequestBody Map<String, Object> body) {
        AuthUser u = CurrentUser.get(request);
        String title = (String) body.get("title");
        Long courseId = body.get("courseId") == null ? null : Long.valueOf(String.valueOf(body.get("courseId")));
        Long kbId = body.get("kbId") == null ? null : Long.valueOf(String.valueOf(body.get("kbId")));
        return ApiResponse.ok(chatService.updateSession(id, u.id(), title, courseId, kbId));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(HttpServletRequest request, @PathVariable Long id) {
        AuthUser u = CurrentUser.get(request);
        chatService.deleteSession(id, u.id());
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/stream")
    public SseEmitter stream(HttpServletRequest request, @PathVariable Long id, @RequestBody Map<String, String> body) {
        AuthUser u = CurrentUser.get(request);
        SseEmitter emitter = new SseEmitter(300_000L);
        String question = body.get("message");
        if (question == null || question.isBlank()) {
            try {
                emitter.send(SseEmitter.event().data(Map.of("sources", "")));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }
        chatService.streamMessage(id, question, u.id(),
                delta -> safeSend(emitter, Map.of("delta", delta)),
                sources -> {
                    try {
                        emitter.send(SseEmitter.event().data(Map.of("sources", sources == null ? "" : sources)));
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                        return;
                    }
                    emitter.complete();
                });
        return emitter;
    }

    private void safeSend(SseEmitter emitter, Object data) {
        try {
            emitter.send(SseEmitter.event().data(data));
        } catch (IOException e) {
            log.warn("SSE send 失败: {}", e.getMessage());
            emitter.completeWithError(e);
        } catch (Exception e) {
            log.warn("SSE send 异常: {}", e.getMessage());
        }
    }
}
