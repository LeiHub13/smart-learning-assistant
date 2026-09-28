package com.example.learningassistant.hall.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.learningassistant.common.BizException;
import com.example.learningassistant.hall.entity.DmMessage;
import com.example.learningassistant.hall.entity.Friendship;
import com.example.learningassistant.hall.mapper.DmMessageMapper;
import com.example.learningassistant.hall.mapper.FriendshipMapper;
import com.example.learningassistant.hall.web.HallWebSocketHandler;
import com.example.learningassistant.notify.service.NotifyService;
import com.example.learningassistant.user.entity.User;
import com.example.learningassistant.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 好友关系：请求-同意模式。同一对用户的请求按方向各最多一条（唯一键 user_id+friend_id），
 * 同意后双方皆可按 (me,other)|(other,me) 查到这条已同意关系。
 */
@Service
@RequiredArgsConstructor
public class FriendService {

    private final FriendshipMapper friendshipMapper;
    private final DmMessageMapper dmMessageMapper;
    private final UserMapper userMapper;
    private final NotifyService notifyService;
    private final HallWebSocketHandler wsHandler;

    /** 好友列表（含在线标记与未读私信数）+ 待处理请求（收到的与发出的）。 */
    public Map<String, Object> list(Long me) {
        List<Friendship> accepted = friendshipMapper.selectList(new LambdaQueryWrapper<Friendship>()
                .and(w -> w.eq(Friendship::getUserId, me).or().eq(Friendship::getFriendId, me))
                .eq(Friendship::getStatus, Friendship.STATUS_ACCEPTED));
        Map<Long, Friendship> rowByFriend = new LinkedHashMap<>();
        for (Friendship f : accepted) {
            rowByFriend.put(me.equals(f.getUserId()) ? f.getFriendId() : f.getUserId(), f);
        }
        Set<Long> online = wsHandler.onlineUserIds();

        List<Map<String, Object>> friends = new ArrayList<>();
        for (Map.Entry<Long, Friendship> e : rowByFriend.entrySet()) {
            Long fid = e.getKey();
            User u = userMapper.selectById(fid);
            if (u == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("requestId", e.getValue().getId());
            m.put("userId", fid);
            m.put("username", u.getUsername());
            m.put("nickname", display(u));
            m.put("online", online.contains(fid));
            m.put("unread", dmMessageMapper.selectCount(new LambdaQueryWrapper<DmMessage>()
                    .eq(DmMessage::getSenderId, fid)
                    .eq(DmMessage::getReceiverId, me)
                    .eq(DmMessage::getReadFlag, 0)));
            friends.add(m);
        }

        List<Map<String, Object>> incoming = new ArrayList<>();
        for (Friendship f : friendshipMapper.selectList(new LambdaQueryWrapper<Friendship>()
                .eq(Friendship::getFriendId, me)
                .eq(Friendship::getStatus, Friendship.STATUS_PENDING))) {
            User u = userMapper.selectById(f.getUserId());
            if (u != null) {
                incoming.add(requestView(f.getId(), u));
            }
        }
        List<Map<String, Object>> outgoing = new ArrayList<>();
        for (Friendship f : friendshipMapper.selectList(new LambdaQueryWrapper<Friendship>()
                .eq(Friendship::getUserId, me)
                .eq(Friendship::getStatus, Friendship.STATUS_PENDING))) {
            User u = userMapper.selectById(f.getFriendId());
            if (u != null) {
                outgoing.add(requestView(f.getId(), u));
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("friends", friends);
        result.put("incoming", incoming);
        result.put("outgoing", outgoing);
        return result;
    }

    /** 按用户名发好友请求；请求本身给对方发一条铃铛通知。 */
    public void request(Long me, String username) {
        User target = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username));
        if (target == null) {
            throw new BizException("用户不存在：" + username);
        }
        if (target.getId().equals(me)) {
            throw new BizException("不能添加自己为好友");
        }
        // 双向各一条独立查询：组合 OR 的嵌套 wrapper 在该表上匹配不到行，弃用
        boolean exists = friendshipMapper.selectCount(new LambdaQueryWrapper<Friendship>()
                .eq(Friendship::getUserId, me).eq(Friendship::getFriendId, target.getId())) > 0
                || friendshipMapper.selectCount(new LambdaQueryWrapper<Friendship>()
                .eq(Friendship::getUserId, target.getId()).eq(Friendship::getFriendId, me)) > 0;
        if (exists) {
            throw new BizException("已存在好友关系或待处理的请求");
        }
        Friendship f = new Friendship();
        f.setTenantId(target.getTenantId());
        f.setUserId(me);
        f.setFriendId(target.getId());
        f.setStatus(Friendship.STATUS_PENDING);
        f.setCreatedAt(LocalDateTime.now());
        friendshipMapper.insert(f);
        notifyService.send(target.getId(), "friend", "好友请求",
                display(meUser(me)) + " 请求加你为好友", "/hall");
    }

    public void accept(Long me, Long requestId) {
        Friendship f = pendingForMe(me, requestId);
        f.setStatus(Friendship.STATUS_ACCEPTED);
        friendshipMapper.updateById(f);
        notifyService.send(f.getUserId(), "friend", "好友请求已同意",
                display(meUser(me)) + " 已同意你的好友请求", "/hall");
    }

    /** 拒绝请求：直接删掉这条待处理关系，双方都可重新发起。 */
    public void reject(Long me, Long requestId) {
        friendshipMapper.deleteById(pendingForMe(me, requestId).getId());
    }

    public void remove(Long me, Long requestId) {
        Friendship f = friendshipMapper.selectById(requestId);
        if (f == null || (!f.getUserId().equals(me) && !f.getFriendId().equals(me))) {
            throw new BizException("好友关系不存在");
        }
        if (f.getStatus() != Friendship.STATUS_ACCEPTED) {
            throw new BizException("该请求尚未同意，请在请求列表中处理");
        }
        friendshipMapper.deleteById(f.getId());
    }

    private Friendship pendingForMe(Long me, Long requestId) {
        Friendship f = friendshipMapper.selectById(requestId);
        if (f == null || !f.getFriendId().equals(me) || f.getStatus() != Friendship.STATUS_PENDING) {
            throw new BizException("好友请求不存在或已处理");
        }
        return f;
    }

    private User meUser(Long me) {
        User u = userMapper.selectById(me);
        if (u == null) {
            throw new BizException("用户不存在");
        }
        return u;
    }

    private static String display(User u) {
        return u.getNickname() == null || u.getNickname().isBlank() ? u.getUsername() : u.getNickname();
    }

    private Map<String, Object> requestView(Long requestId, User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requestId", requestId);
        m.put("userId", u.getId());
        m.put("username", u.getUsername());
        m.put("nickname", display(u));
        return m;
    }
}
