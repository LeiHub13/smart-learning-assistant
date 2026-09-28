package com.example.learningassistant.hall.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.learningassistant.course.entity.Course;
import com.example.learningassistant.course.entity.CourseUser;
import com.example.learningassistant.course.mapper.CourseMapper;
import com.example.learningassistant.course.mapper.CourseUserMapper;
import com.example.learningassistant.hall.entity.DmMessage;
import com.example.learningassistant.hall.entity.Friendship;
import com.example.learningassistant.hall.entity.HallMessage;
import com.example.learningassistant.hall.mapper.DmMessageMapper;
import com.example.learningassistant.hall.mapper.FriendshipMapper;
import com.example.learningassistant.hall.mapper.HallMessageMapper;
import com.example.learningassistant.notify.service.NotifyService;
import com.example.learningassistant.security.AuthUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * 对话厅 WebSocket 处理器：频道聊天（公共大厅 + 课程频道）与好友私信复用一条连接。
 *
 * 协议（JSON 文本帧）：
 *   客户端 -> {type:"chat", courseId?, content:"..."}  courseId 缺省=公共大厅，否则须为我的课程
 *            {type:"dm", toUserId, content:"..."}      仅限已同意的好友
 *            {type:"view", kind:"dm"|"channel", peerUserId?}  上报当前正在看的会话（DM 免打扰判定）
 *            {type:"ping"}
 *   服务端 -> {type:"chat", id, userId, nickname, courseId, content, sentAt}
 *            {type:"dm", id, fromUserId, fromNickname, content, sentAt}   收发双方都收（多端一致）
 *            {type:"system", event:"join"|"leave", nickname, online:[{userId,nickname}], onlineCount}
 *            {type:"pong"} / {type:"error", message}
 * 课程成员集合在连接建立时缓存（选课变化重连后生效）；课程频道消息只广播给同课程在线者，
 * 公共大厅消息全员广播。DM 一律落铃铛通知，收件人正在查看该会话时视为已读不打铃。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HallWebSocketHandler extends TextWebSocketHandler {

    /** 握手后写入 session attributes 的当前用户（由 HallHandshakeInterceptor 鉴权后放入）。 */
    public static final String ATTR_USER = "hallUser";

    static final int MAX_CONTENT_LEN = 500;

    private final ObjectMapper objectMapper;
    private final HallMessageMapper hallMessageMapper;
    private final DmMessageMapper dmMessageMapper;
    private final FriendshipMapper friendshipMapper;
    private final CourseMapper courseMapper;
    private final CourseUserMapper courseUserMapper;
    private final NotifyService notifyService;

    /** 在线连接 -> 用户。ConcurrentHashMap keySet 天然去重并发。 */
    private final Map<WebSocketSession, AuthUser> sessions = new ConcurrentHashMap<>();

    /** 在线用户的课程集合缓存（userId -> 我创建/已加入的课程 id），连接建立时加载。 */
    private final Map<Long, Set<Long>> userCourses = new ConcurrentHashMap<>();

    /** 正在查看的会话（userId -> 对端 userId）；查看频道或断开时移除。用于 DM 免打扰判定。 */
    private final Map<Long, Long> viewingPeer = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        AuthUser user = user(session);
        if (user == null) {
            closeQuietly(session);
            return;
        }
        sessions.put(session, user);
        userCourses.put(user.id(), loadMyCourseIds(user.id()));
        broadcastSystem("join", nickname(user));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<?, ?> payload;
        try {
            payload = objectMapper.readValue(message.getPayload(), Map.class);
        } catch (Exception e) {
            return;
        }
        String type = String.valueOf(payload.get("type"));
        if ("ping".equals(type)) {
            sendTo(session, Map.of("type", "pong"));
            return;
        }
        AuthUser user = user(session);
        if (user == null) {
            return;
        }
        if ("view".equals(type)) {
            Long peer = toLong(payload.get("peerUserId"));
            if ("dm".equals(String.valueOf(payload.get("kind"))) && peer != null) {
                viewingPeer.put(user.id(), peer);
            } else {
                viewingPeer.remove(user.id());
            }
            return;
        }
        Object raw = payload.get("content");
        String content = raw == null ? "" : String.valueOf(raw).trim();
        if (content.isEmpty()) {
            sendTo(session, Map.of("type", "error", "message", "消息不能为空"));
            return;
        }
        if (content.length() > MAX_CONTENT_LEN) {
            sendTo(session, Map.of("type", "error", "message", "消息不能超过 " + MAX_CONTENT_LEN + " 字"));
            return;
        }
        if ("dm".equals(type)) {
            handleDm(session, user, payload.get("toUserId"), content);
        } else if ("chat".equals(type)) {
            handleChat(session, user, payload.get("courseId"), content);
        }
    }

    /** 频道消息：courseId 缺省=公共大厅；课程频道须为成员，且只广播给同课程在线者。 */
    private void handleChat(WebSocketSession session, AuthUser user, Object rawCourseId, String content) {
        Long courseId = toLong(rawCourseId);
        if (courseId != null && !myCourses(user.id()).contains(courseId)) {
            sendTo(session, Map.of("type", "error", "message", "只能在自己创建或已加入的课程频道发言"));
            return;
        }
        HallMessage m = new HallMessage();
        m.setTenantId(user.tenantId());
        m.setUserId(user.id());
        m.setCourseId(courseId);
        m.setContent(content);
        m.setCreatedAt(LocalDateTime.now());
        hallMessageMapper.insert(m);

        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "chat");
        frame.put("id", m.getId());
        frame.put("userId", user.id());
        frame.put("nickname", nickname(user));
        frame.put("courseId", courseId);
        frame.put("content", content);
        frame.put("sentAt", m.getCreatedAt());
        // 公共大厅全员可见；课程频道只发给该课程成员（用 Predicate 复用同一条广播路径）
        Predicate<AuthUser> audience = courseId == null
                ? u -> true
                : u -> myCourses(u.id()).contains(courseId);
        broadcast(frame, audience);
    }

    /** 私聊：仅限已同意的好友；实时推送双方。收件人未在看的会话一律落铃铛通知（在线与否不影响）。 */
    private void handleDm(WebSocketSession session, AuthUser user, Object rawTo, String content) {
        Long toUserId = toLong(rawTo);
        if (toUserId == null || toUserId.equals(user.id())) {
            sendTo(session, Map.of("type", "error", "message", "无效的私聊对象"));
            return;
        }
        if (!areFriends(user.id(), toUserId)) {
            sendTo(session, Map.of("type", "error", "message", "只能给好友发私信"));
            return;
        }
        DmMessage m = new DmMessage();
        m.setTenantId(user.tenantId());
        m.setSenderId(user.id());
        m.setReceiverId(toUserId);
        m.setContent(content);
        m.setReadFlag(0);
        m.setCreatedAt(LocalDateTime.now());
        dmMessageMapper.insert(m);

        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "dm");
        frame.put("id", m.getId());
        frame.put("fromUserId", user.id());
        frame.put("fromNickname", nickname(user));
        frame.put("toUserId", toUserId);
        frame.put("content", content);
        frame.put("sentAt", m.getCreatedAt());

        sendToUser(toUserId, frame);
        sendToUser(user.id(), frame);

        // 收件人正开着这个会话 = 消息已实时看见，直接置已读且不打铃；否则一律通知
        boolean viewed = user.id().equals(viewingPeer.get(toUserId));
        if (viewed) {
            dmMessageMapper.update(null, new UpdateWrapper<DmMessage>()
                    .set("read_flag", 1)
                    .eq("id", m.getId()));
        } else {
            notifyService.send(toUserId, "dm", "来自 " + nickname(user) + " 的私信",
                    content, "/hall?peer=" + user.id());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        AuthUser user = sessions.remove(session);
        if (user == null) {
            return;
        }
        boolean stillOnline = sessions.values().stream().anyMatch(u -> u.id().equals(user.id()));
        if (!stillOnline) {
            userCourses.remove(user.id());
            viewingPeer.remove(user.id());
        }
        broadcastSystem("leave", nickname(user));
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        // 异常连接交给容器按 idle 超时或客户端 onClose 清理，这里不重复 close 以免噪音
        log.debug("对话厅连接异常: {}", exception.getMessage());
    }

    /** 当前在线用户 id（好友列表的在线标记用）。 */
    public Set<Long> onlineUserIds() {
        Set<Long> ids = new HashSet<>();
        for (AuthUser u : sessions.values()) {
            ids.add(u.id());
        }
        return ids;
    }

    private Set<Long> myCourses(Long userId) {
        return userCourses.computeIfAbsent(userId, this::loadMyCourseIds);
    }

    /** 我创建的 + 已加入的课程（口径同「我的课程」）。 */
    private Set<Long> loadMyCourseIds(Long userId) {
        Set<Long> ids = new HashSet<>();
        courseMapper.selectList(new LambdaQueryWrapper<Course>()
                        .eq(Course::getOwnerId, userId).select(Course::getId))
                .forEach(c -> ids.add(c.getId()));
        courseUserMapper.selectList(new LambdaQueryWrapper<CourseUser>()
                        .eq(CourseUser::getUserId, userId).select(CourseUser::getCourseId))
                .forEach(cu -> ids.add(cu.getCourseId()));
        return ids;
    }

    private boolean areFriends(Long a, Long b) {
        // 双向各一条独立查询：组合 OR 的嵌套 wrapper 在该表上匹配不到行，弃用
        return friendshipMapper.selectCount(new LambdaQueryWrapper<Friendship>()
                .eq(Friendship::getUserId, a).eq(Friendship::getFriendId, b)
                .eq(Friendship::getStatus, Friendship.STATUS_ACCEPTED)) > 0
                || friendshipMapper.selectCount(new LambdaQueryWrapper<Friendship>()
                .eq(Friendship::getUserId, b).eq(Friendship::getFriendId, a)
                .eq(Friendship::getStatus, Friendship.STATUS_ACCEPTED)) > 0;
    }

    private AuthUser user(WebSocketSession session) {
        Object u = session.getAttributes().get(ATTR_USER);
        return u instanceof AuthUser a ? a : null;
    }

    private static String nickname(AuthUser user) {
        String n = user.nickname();
        return n == null || n.isBlank() ? user.username() : n;
    }

    private static Long toLong(Object raw) {
        try {
            return raw == null ? null : Long.valueOf(String.valueOf(raw));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 在线名单：按 userId 去重，同一用户多端连接只显示一次。 */
    private List<Map<String, Object>> onlineUsers() {
        Map<Long, String> byUser = new LinkedHashMap<>();
        for (AuthUser u : sessions.values()) {
            byUser.putIfAbsent(u.id(), nickname(u));
        }
        List<Map<String, Object>> list = new ArrayList<>();
        byUser.forEach((id, nick) -> list.add(Map.of("userId", id, "nickname", nick)));
        return list;
    }

    private void broadcastSystem(String event, String nickname) {
        List<Map<String, Object>> online = onlineUsers();
        broadcast(Map.of("type", "system", "event", event, "nickname", nickname,
                "online", online, "onlineCount", online.size()), u -> true);
    }

    private void broadcast(Map<String, Object> payload, Predicate<AuthUser> audience) {
        String json = toJson(payload);
        for (Map.Entry<WebSocketSession, AuthUser> e : sessions.entrySet()) {
            if (audience.test(e.getValue())) {
                sendRaw(e.getKey(), json);
            }
        }
    }

    /** 发给某用户的全部在线连接；返回其是否至少有一条在线。 */
    private boolean sendToUser(Long userId, Map<String, Object> payload) {
        String json = toJson(payload);
        boolean any = false;
        for (Map.Entry<WebSocketSession, AuthUser> e : sessions.entrySet()) {
            if (e.getValue().id().equals(userId)) {
                any = true;
                sendRaw(e.getKey(), json);
            }
        }
        return any;
    }

    private void sendTo(WebSocketSession session, Map<String, Object> payload) {
        sendRaw(session, toJson(payload));
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("对话厅消息序列化失败", e);
        }
    }

    /**
     * sendMessage 非线程安全，按 session 对象串行化；IO 失败视为连接已死，
     * 就地移除并广播离开（afterConnectionClosed 随后触发时 sessions 里已不在，不会重复广播）。
     */
    private void sendRaw(WebSocketSession session, String json) {
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (Exception e) {
            log.debug("对话厅推送失败，移除连接: {}", e.getMessage());
            AuthUser gone = sessions.remove(session);
            if (gone != null) {
                broadcastSystem("leave", nickname(gone));
            }
        }
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close();
        } catch (Exception ignored) {
        }
    }
}
