package com.example.learningassistant.web.controller;

import com.example.learningassistant.agent.entity.AgentAction;
import com.example.learningassistant.agent.service.AgentActionService;
import com.example.learningassistant.common.ApiResponse;
import com.example.learningassistant.security.AuthUser;
import com.example.learningassistant.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Agent 待确认动作的前端确认接口：列表 / 确认执行 / 取消。
 * userId 一律取自登录态（CurrentUser），动作归属在服务层二次校验。
 */
@RestController
@RequestMapping("/api/agent/actions")
@RequiredArgsConstructor
public class AgentActionController {

    private final AgentActionService agentActionService;

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> pending(HttpServletRequest request,
                                                          @RequestParam(required = false) Long sessionId) {
        AuthUser u = CurrentUser.get(request);
        List<Map<String, Object>> views = agentActionService.pendingFor(u.id(), sessionId).stream()
                .map(AgentActionService::toView)
                .toList();
        return ApiResponse.ok(views);
    }

    @PostMapping("/{id}/confirm")
    public ApiResponse<Map<String, Object>> confirm(HttpServletRequest request, @PathVariable Long id) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(agentActionService.confirm(u.id(), id));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<Void> cancel(HttpServletRequest request, @PathVariable Long id) {
        AuthUser u = CurrentUser.get(request);
        agentActionService.cancel(u.id(), id);
        return ApiResponse.ok(null);
    }
}
