package com.example.learningassistant.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.learningassistant.common.ApiResponse;
import com.example.learningassistant.common.BizException;
import com.example.learningassistant.hall.entity.DmMessage;
import com.example.learningassistant.hall.entity.Friendship;
import com.example.learningassistant.hall.mapper.DmMessageMapper;
import com.example.learningassistant.hall.mapper.FriendshipMapper;
import com.example.learningassistant.notify.service.NotifyService;
import com.example.learningassistant.security.AuthUser;
import com.example.learningassistant.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 一对一私信：历史回看（打开会话即把对方发来的未读标为已读；实时推送走 /ws/hall 的 dm 帧）。
 */
@RestController
@RequestMapping("/api/dm")
@RequiredArgsConstructor
public class DmController {

    private static final int MAX_LIMIT = 200;

    private final DmMessageMapper dmMessageMapper;
    private final FriendshipMapper friendshipMapper;
    private final NotifyService notifyService;

    @GetMapping("/messages")
    public ApiResponse<List<DmMessageView>> messages(@RequestParam Long peerUserId,
                                                     @RequestParam(defaultValue = "50") int limit,
                                                     HttpServletRequest request) {
        AuthUser me = CurrentUser.get(request);
        int n = Math.min(Math.max(limit, 1), MAX_LIMIT);
        if (!areFriends(me.id(), peerUserId)) {
            throw new BizException("只能查看与好友的私聊");
        }

        // 两个方向各查一次再归并：组合 OR 的嵌套 wrapper 在该表上匹配不到行，弃用
        List<DmMessage> sent = dmMessageMapper.selectList(new LambdaQueryWrapper<DmMessage>()
                .eq(DmMessage::getSenderId, me.id()).eq(DmMessage::getReceiverId, peerUserId)
                .orderByDesc(DmMessage::getId).last("LIMIT " + n));
        List<DmMessage> received = dmMessageMapper.selectList(new LambdaQueryWrapper<DmMessage>()
                .eq(DmMessage::getSenderId, peerUserId).eq(DmMessage::getReceiverId, me.id())
                .orderByDesc(DmMessage::getId).last("LIMIT " + n));
        ArrayList<DmMessage> ordered = new ArrayList<>(sent.size() + received.size());
        ordered.addAll(sent);
        ordered.addAll(received);
        ordered.sort((a, b) -> Long.compare(a.getId(), b.getId()));
        if (ordered.size() > n) {
            ordered = new ArrayList<>(ordered.subList(ordered.size() - n, ordered.size()));
        }

        // 打开会话即已读：只把对方发来的未读行置 1（显式 SET，避免 updateById 跳过值不变的列），
        // 同时把铃铛里指向该会话的未读通知一并清掉
        dmMessageMapper.update(null, new UpdateWrapper<DmMessage>()
                .set("read_flag", 1)
                .eq("sender_id", peerUserId)
                .eq("receiver_id", me.id())
                .eq("read_flag", 0));
        notifyService.markLinkRead(me.id(), "/hall?peer=" + peerUserId);

        return ApiResponse.ok(ordered.stream()
                .map(m -> new DmMessageView(m.getId(), m.getSenderId(), m.getContent(), m.getCreatedAt()))
                .toList());
    }

    /** 双向各一条独立查询（同 areFriends 口径），任一方向存在已同意关系即为好友。 */
    private boolean areFriends(Long a, Long b) {
        return friendshipMapper.selectCount(new LambdaQueryWrapper<Friendship>()
                .eq(Friendship::getUserId, a).eq(Friendship::getFriendId, b)
                .eq(Friendship::getStatus, Friendship.STATUS_ACCEPTED)) > 0
                || friendshipMapper.selectCount(new LambdaQueryWrapper<Friendship>()
                .eq(Friendship::getUserId, b).eq(Friendship::getFriendId, a)
                .eq(Friendship::getStatus, Friendship.STATUS_ACCEPTED)) > 0;
    }

    /** fromUserId 为发送者；是否本人消息由前端与当前用户比对。 */
    public record DmMessageView(Long id, Long fromUserId, String content, LocalDateTime createdAt) {
    }
}
