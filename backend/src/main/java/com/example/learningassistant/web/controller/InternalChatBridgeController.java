package com.example.learningassistant.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.learningassistant.agent.service.AgentActionService;
import com.example.learningassistant.billing.BillingService;
import com.example.learningassistant.common.ApiResponse;
import com.example.learningassistant.common.BizException;
import com.example.learningassistant.course.entity.Course;
import com.example.learningassistant.course.entity.CourseUser;
import com.example.learningassistant.course.mapper.CourseMapper;
import com.example.learningassistant.course.mapper.CourseUserMapper;
import com.example.learningassistant.kb.entity.KnowledgeBase;
import com.example.learningassistant.kb.mapper.KnowledgeBaseMapper;
import com.example.learningassistant.progress.entity.KnowledgeMastery;
import com.example.learningassistant.progress.mapper.KnowledgeMasteryMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 供 Python 答疑服务访问 Java 所有的课程、动作和次数能力，不通过 Nginx 暴露。 */
@RestController
@RequestMapping("/internal/chat")
@RequiredArgsConstructor
public class InternalChatBridgeController {
    private final CourseMapper courses;
    private final CourseUserMapper memberships;
    private final KnowledgeBaseMapper knowledgeBases;
    private final KnowledgeMasteryMapper mastery;
    private final AgentActionService actions;
    private final BillingService billing;

    @Value("${app.internal-tool-token:internal-tool-token}")
    private String internalToken;

    private void authenticate(HttpServletRequest request) {
        String supplied = request.getHeader("X-Internal-Token");
        if (supplied == null || !MessageDigest.isEqual(internalToken.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new BizException(403, "内部接口鉴权失败");
        }
    }

    /** 校验课程归属与知识库范围，不能信任客户端传来的课程和知识库 ID。 */
    @GetMapping("/context")
    public ApiResponse<Map<String, Object>> context(HttpServletRequest request,
            @RequestParam Long userId, @RequestParam(required = false) Long courseId,
            @RequestParam(required = false) Long kbId, @RequestParam(required = false) String kbScope) {
        authenticate(request);
        KnowledgeBase kb = kbId == null ? null : knowledgeBases.selectById(kbId);
        if (kbId != null && kb == null) throw new BizException("知识库不存在");
        Long resolvedCourseId = courseId == null && kb != null ? kb.getCourseId() : courseId;
        if (kb != null && (resolvedCourseId == null || !resolvedCourseId.equals(kb.getCourseId()))) {
            throw new BizException("知识库不属于所选课程");
        }
        if (resolvedCourseId != null) {
            Course course = courses.selectById(resolvedCourseId);
            if (course == null) throw new BizException("课程不存在");
            boolean owner = userId.equals(course.getOwnerId());
            boolean member = memberships.selectCount(new LambdaQueryWrapper<CourseUser>()
                    .eq(CourseUser::getCourseId, resolvedCourseId).eq(CourseUser::getUserId, userId)) > 0;
            if (!owner && !member) throw new BizException(403, "无权访问该课程");
        }
        List<Long> kbIds = kb != null && "course".equals(kbScope)
                ? knowledgeBases.selectList(new LambdaQueryWrapper<KnowledgeBase>()
                    .eq(KnowledgeBase::getCourseId, resolvedCourseId)).stream().map(KnowledgeBase::getId).toList()
                : List.of();
        StringBuilder note = new StringBuilder();
        if (resolvedCourseId != null) {
            List<KnowledgeMastery> weak = mastery.selectList(new LambdaQueryWrapper<KnowledgeMastery>()
                    .eq(KnowledgeMastery::getUserId, userId).eq(KnowledgeMastery::getCourseId, resolvedCourseId)
                    .lt(KnowledgeMastery::getMastery, 50.0).orderByAsc(KnowledgeMastery::getMastery).last("LIMIT 5"));
            if (!weak.isEmpty()) {
                note.append("【该学生薄弱知识点（掌握度<50%）】");
                for (KnowledgeMastery item : weak) {
                    note.append(item.getKpName()).append("(").append(Math.round(item.getMastery())).append("%); ");
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("courseId", resolvedCourseId);
        result.put("kbId", kbId);
        result.put("kbName", kb == null ? null : kb.getName());
        result.put("kbIds", kbIds);
        result.put("note", note.toString());
        return ApiResponse.ok(result);
    }

    @GetMapping("/actions")
    public ApiResponse<List<Map<String, Object>>> pendingActions(HttpServletRequest request,
            @RequestParam Long userId, @RequestParam Long sessionId) {
        authenticate(request);
        return ApiResponse.ok(actions.pendingFor(userId, sessionId).stream().map(AgentActionService::toView).toList());
    }

    @PostMapping("/billing/reserve")
    public ApiResponse<Map<String, Object>> reserve(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        authenticate(request);
        Long userId = Long.valueOf(String.valueOf(body.get("userId")));
        String turnId = UUID.fromString(String.valueOf(body.get("turnId"))).toString();
        String reservation = billing.reserveWithId(userId, "chat", turnId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reservationId", reservation);
        return ApiResponse.ok(result);
    }

    @PostMapping("/billing/{reservation}/settle")
    public ApiResponse<Void> settle(HttpServletRequest request, @PathVariable String reservation) {
        authenticate(request);
        billing.settle(UUID.fromString(reservation).toString());
        return ApiResponse.ok(null);
    }

    @PostMapping("/billing/{reservation}/release")
    public ApiResponse<Void> release(HttpServletRequest request, @PathVariable String reservation,
                                     @RequestBody Map<String, Object> body) {
        authenticate(request);
        billing.releaseChat(Long.valueOf(String.valueOf(body.get("userId"))), UUID.fromString(reservation).toString());
        return ApiResponse.ok(null);
    }
}
