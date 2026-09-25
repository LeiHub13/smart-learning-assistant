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

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }
        // 本轮起点：只把本轮新登记的动作挂到这条回复上，否则上一轮未确认的动作会在每条消息下重复出现
        java.time.LocalDateTime turnStart = java.time.LocalDateTime.now();
        AtomicBoolean clientGone = new AtomicBoolean(false);
        chatService.streamMessage(id, question, u.id(),
                delta -> safeSend(emitter, clientGone, Map.of("delta", delta)),
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
                    safeSend(emitter, clientGone, Map.of(
                            "sources", sources == null ? "" : sources, "actions", actions));
                    safeComplete(emitter, clientGone);
                });
        return emitter;
    }

    private static Long asLong(Object v) {
        return v == null ? null : Long.valueOf(String.valueOf(v));
    }

    /**
     * 推送一条 SSE 事件。失败（典型为客户端生成中途刷新/离开页面导致断连）只标记失效并跳过后续推送，
     * 绝不再触碰 emitter：断连后 async context 已处于 error 态，此时连 completeWithError 都会抛
     * IllegalStateException——一旦从生成回调逃逸进生成线程，会把生成、落库、ai-service 记忆整条链路
     * 中断掉（表现为用户回到会话后看不到这条回复）。断开只影响推送，不影响生成与持久化。
     */
    private void safeSend(SseEmitter emitter, AtomicBoolean clientGone, Object data) {
        if (clientGone.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().data(data));
        } catch (Exception e) {
            clientGone.set(true);
            log.warn("SSE 推送失败，客户端可能已断开（生成与落库继续）: {}", e.getMessage());
        }
    }

    private void safeComplete(SseEmitter emitter, AtomicBoolean clientGone) {
        if (clientGone.get()) {
            return;
        }
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("SSE complete 失败（忽略）: {}", e.getMessage());
        }
    }
}
