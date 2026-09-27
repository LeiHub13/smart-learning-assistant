package com.example.learningassistant.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.learningassistant.common.ApiResponse;
import com.example.learningassistant.hall.entity.HallMessage;
import com.example.learningassistant.hall.mapper.HallMessageMapper;
import com.example.learningassistant.user.entity.User;
import com.example.learningassistant.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 对话厅历史消息：进厅时回看最近聊天（实时部分走 WebSocket /ws/hall）。
 */
@RestController
@RequestMapping("/api/hall")
@RequiredArgsConstructor
public class HallController {

    private static final int MAX_LIMIT = 200;

    private final HallMessageMapper hallMessageMapper;
    private final UserMapper userMapper;

    /** 最近 N 条（默认 50，倒序取完反转为正序），昵称批量补齐。 */
    @GetMapping("/messages")
    public ApiResponse<List<HallMessageView>> messages(@RequestParam(defaultValue = "50") int limit) {
        int n = Math.min(Math.max(limit, 1), MAX_LIMIT);
        List<HallMessage> latest = hallMessageMapper.selectList(new LambdaQueryWrapper<HallMessage>()
                .orderByDesc(HallMessage::getId)
                .last("LIMIT " + n));
        List<HallMessage> ordered = new ArrayList<>(latest);
        ordered.sort((a, b) -> Long.compare(a.getId(), b.getId()));

        List<Long> userIds = ordered.stream().map(HallMessage::getUserId).distinct().toList();
        Map<Long, String> nicknames = userIds.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(userIds).stream()
                        .collect(Collectors.toMap(User::getId,
                                u -> u.getNickname() == null || u.getNickname().isBlank()
                                        ? u.getUsername() : u.getNickname(),
                                (a, b) -> a));
        return ApiResponse.ok(ordered.stream()
                .map(m -> new HallMessageView(m.getId(), m.getUserId(),
                        nicknames.getOrDefault(m.getUserId(), "用户" + m.getUserId()),
                        m.getContent(), m.getCreatedAt()))
                .toList());
    }

    /** 历史消息视图：昵称在读取时补齐，落库只存 userId。 */
    public record HallMessageView(Long id, Long userId, String nickname, String content,
                                  LocalDateTime createdAt) {
    }
}
