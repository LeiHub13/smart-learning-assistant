package com.example.learningassistant.web.controller;

import com.example.learningassistant.agent.service.AgentActionService;
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
    private final AgentActionService agentActionService;

    @GetMapping
    public ApiResponse<List<ChatSession>> sessions(HttpServletRequest request) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(chatService.sessions(u.id()));
    }

    @PostMapping
    public ApiResponse<ChatSession> create(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(chatService.createSession(u.id(), asLong(body.get("courseId")),
                asLong(body.get("kbId")), (String) body.get("kbScope")));
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
        return ApiResponse.ok(chatService.updateSession(id, u.id(), (String) body.get("title"),
                asLong(body.get("courseId")), asLong(body.get("kbId")), (String) body.get("kbScope")));
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
        // 本轮起点：只把本轮新登记的动作挂到这条回复上，否则上一轮未确认的动作会在每条消息下重复出现
        java.time.LocalDateTime turnStart = java.time.LocalDateTime.now();
        chatService.streamMessage(id, question, u.id(),
                delta -> safeSend(emitter, Map.of("delta", delta)),
                sources -> {
                    // 同一条 done 事件里附带本轮登记的待确认动作（写操作只 proposal，不自动落库）
                    List<Map<String, Object>> actions;
                    try {
                        actions = agentActionService.pendingFor(u.id(), id).stream()
                                .filter(a -> a.getCreatedAt() == null || !a.getCreatedAt().isBefore(turnStart))
                                .map(AgentActionService::toView)
                                .toList();
                    } catch (RuntimeException e) {
                        log.warn("查询待确认动作失败: {}", e.getMessage());
                        actions = List.of();
                    }
                    try {
                        emitter.send(SseEmitter.event().data(
                                Map.of("sources", sources == null ? "" : sources, "actions", actions)));
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                        return;
                    }
                    emitter.complete();
                });
        return emitter;
    }

    private static Long asLong(Object v) {
        return v == null ? null : Long.valueOf(String.valueOf(v));
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
