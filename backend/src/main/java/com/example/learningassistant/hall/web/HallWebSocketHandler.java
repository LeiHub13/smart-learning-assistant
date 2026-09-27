package com.example.learningassistant.hall.web;

import com.example.learningassistant.hall.entity.HallMessage;
import com.example.learningassistant.hall.mapper.HallMessageMapper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话厅 WebSocket 处理器：全局单间，进厅广播在线名单，发言全员广播并落库。
 *
 * 协议（JSON 文本帧）：
 *   客户端 -> {type:"chat", content:"..."} / {type:"ping"}
 *   服务端 -> {type:"chat", id, userId, nickname, content, sentAt}   聊天消息（含发送者自己的回显）
 *            {type:"system", event:"join"|"leave", nickname, online:[{userId,nickname}], onlineCount}
 *            {type:"pong"} / {type:"error", message}
 * 在线名单按 userId 去重（同一用户开多个标签页只算一人）。
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

    /** 在线连接 -> 用户。ConcurrentHashMap keySet 天然去重并发。 */
    private final Map<WebSocketSession, AuthUser> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        AuthUser user = user(session);
        if (user == null) {
            closeQuietly(session);
            return;
        }
        sessions.put(session, user);
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
        if (!"chat".equals(type)) {
            return;
        }
        AuthUser user = user(session);
        if (user == null) {
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
        HallMessage m = new HallMessage();
        m.setTenantId(user.tenantId());
        m.setUserId(user.id());
        m.setContent(content);
        m.setCreatedAt(LocalDateTime.now());
        hallMessageMapper.insert(m);
        broadcast(Map.of("type", "chat", "id", m.getId(), "userId", user.id(),
                "nickname", nickname(user), "content", content, "sentAt", m.getCreatedAt()));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        AuthUser user = sessions.remove(session);
        if (user != null) {
            broadcastSystem("leave", nickname(user));
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        // 异常连接交给容器按 idle 超时或客户端 onClose 清理，这里不重复 close 以免噪音
        log.debug("对话厅连接异常: {}", exception.getMessage());
    }

    private AuthUser user(WebSocketSession session) {
        Object u = session.getAttributes().get(ATTR_USER);
        return u instanceof AuthUser a ? a : null;
    }

    private static String nickname(AuthUser user) {
        String n = user.nickname();
        return n == null || n.isBlank() ? user.username() : n;
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
                "online", online, "onlineCount", online.size()));
    }

    private void broadcast(Map<String, Object> payload) {
        String json = toJson(payload);
        for (WebSocketSession s : sessions.keySet()) {
            sendRaw(s, json);
        }
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
