package com.example.learningassistant.hall.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.learningassistant.ai.AIChatMessage;
import com.example.learningassistant.ai.ChatModelFactory;
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
import com.example.learningassistant.kb.entity.KnowledgeBase;
import com.example.learningassistant.kb.service.KbService;
import com.example.learningassistant.notify.service.NotifyService;
import com.example.learningassistant.security.AuthUser;
import com.example.learningassistant.user.entity.User;
import com.example.learningassistant.user.mapper.UserMapper;
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
 * 对话厅 WebSocket 处理器：频道聊天（公共大厅 + 课程频道）、AI 助教与好友私信复用一条连接。
 *
 * 协议（JSON 文本帧）：
 *   客户端 -> {type:"chat", courseId?, content:"..."}  courseId 缺省=公共大厅，否则须为我的课程
 *            {type:"ai", courseId, content:"..."}      课程频道问 AI 助教（RAG 流式，全员可见）
 *            {type:"dm", toUserId, content:"..."}      仅限已同意的好友
 *            {type:"view", kind:"dm"|"channel", peerUserId?}  上报当前正在看的会话（DM 免打扰判定）
 *            {type:"online", courseId?}                拉取当前频道在线名单（点对点回帧；课程频道仅含该课程在线成员）
 *            {type:"ping"}
 *   服务端 -> {type:"chat", id, userId, nickname, avatar, courseId, content, sentAt}
 *             AI 消息额外带 role:"ai" 与 sources（引用编号），userId=0、nickname=「AI 助教」
 *            {type:"ai_start", courseId}                        助教开始生成（客户端占位「思考中」气泡）
 *            {type:"ai_delta", courseId, delta}                 助教回答增量
 *            {type:"ai_error", courseId, message}               助教生成失败
 *            {type:"online", courseId, online:[...], onlineCount}  在线名单回帧（公共大厅=全员，课程频道=该课程成员）
 *            {type:"dm", id, fromUserId, fromNickname, fromAvatar, content, sentAt}  收发双方都收（多端一致）
 *            {type:"system", event:"join"|"leave", nickname, online:[...], onlineCount}
 *            {type:"pong"} / {type:"error", message}
 * system 帧的 online 仍为全厅名单（移动端单房间版消费）；Web 端课程频道的成员名单走 online 请求帧。
 * 课程成员集合在连接建立时缓存（选课变化重连后生效）；课程频道消息只广播给同课程在线者，
 * 公共大厅消息全员广播。DM 一律落铃铛通知，收件人正在查看该会话时视为已读不打铃。
 * AI 助教每课程单飞（同时只允许一个请求在生成），SESSION_ID=hall-course-{id} 使课程内共享助教记忆。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HallWebSocketHandler extends TextWebSocketHandler {

    /** 握手后写入 session attributes 的当前用户（由 HallHandshakeInterceptor 鉴权后放入）。 */
    public static final String ATTR_USER = "hallUser";

    static final int MAX_CONTENT_LEN = 500;

    /** AI 助教消息展示昵称（userId 固定为 HallMessage.AI_USER_ID） */
    private static final String AI_NICKNAME = "AI 助教";

    private final ObjectMapper objectMapper;
    private final HallMessageMapper hallMessageMapper;
    private final DmMessageMapper dmMessageMapper;
    private final FriendshipMapper friendshipMapper;
    private final CourseMapper courseMapper;
    private final CourseUserMapper courseUserMapper;
    private final UserMapper userMapper;
    private final NotifyService notifyService;
    private final ChatModelFactory modelFactory;
    private final KbService kbService;

    /** 在线连接 -> 用户。ConcurrentHashMap keySet 天然去重并发。 */
    private final Map<WebSocketSession, AuthUser> sessions = new ConcurrentHashMap<>();

    /** 在线用户的课程集合缓存（userId -> 我创建/已加入的课程 id），连接建立时加载。 */
    private final Map<Long, Set<Long>> userCourses = new ConcurrentHashMap<>();

    /** 正在查看的会话（userId -> 对端 userId）；查看频道或断开时移除。用于 DM 免打扰判定。 */
    private final Map<Long, Long> viewingPeer = new ConcurrentHashMap<>();

    /** AI 助教每课程单飞：正在生成的课程 id 集合（add 的原子性即「占坑」判定）。 */
    private final Set<Long> aiBusyCourses = ConcurrentHashMap.newKeySet();

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
        if ("online".equals(type)) {
            // 点对点在线名单：课程频道只含该课程在线成员，且请求者本人须为成员（防非成员探成员名单）
            Long courseId = toLong(payload.get("courseId"));
            boolean member = courseId == null || myCourses(user.id()).contains(courseId);
            List<Map<String, Object>> list = member ? onlineUsersFor(courseId) : List.of();
            Map<String, Object> reply = new LinkedHashMap<>();
            reply.put("type", "online");
            reply.put("courseId", courseId);
            reply.put("online", list);
            reply.put("onlineCount", list.size());
            sendTo(session, reply);
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
        } else if ("ai".equals(type)) {
            handleAi(session, user, payload.get("courseId"), content);
        }
    }

    /** 频道消息：courseId 缺省=公共大厅；课程频道须为成员，且只广播给同课程在线者。 */
    private void handleChat(WebSocketSession session, AuthUser user, Object rawCourseId, String content) {
        Long courseId = toLong(rawCourseId);
        if (courseId != null && !myCourses(user.id()).contains(courseId)) {
            sendTo(session, Map.of("type", "error", "message", "只能在自己创建或已加入的课程频道发言"));
            return;
        }
        HallMessage m = persistMessage(user.tenantId(), user.id(), courseId, HallMessage.ROLE_USER, null, content);
        broadcast(chatFrame(m, nickname(user), null), audience(courseId));
    }

    /**
     * AI 助教：仅课程频道。提问先按普通用户消息落库广播（刷新后问答上下文完整），
     * 随后按课程知识库 RAG 流式回答——delta 增量广播，完成后落库并广播 role=ai 的最终消息。
     * 每课程单飞；无知识库的课程直接提示。
     */
    private void handleAi(WebSocketSession session, AuthUser user, Object rawCourseId, String content) {
        Long courseId = toLong(rawCourseId);
        if (courseId == null) {
            sendTo(session, Map.of("type", "error", "message", "公共大厅没有 AI 助教，请在课程频道提问"));
            return;
        }
        if (!myCourses(user.id()).contains(courseId)) {
            sendTo(session, Map.of("type", "error", "message", "只能在自己创建或已加入的课程频道问助教"));
            return;
        }
        if (!aiBusyCourses.add(courseId)) {
            sendTo(session, Map.of("type", "error", "message", "助教正在回答中，请稍候再提问"));
            return;
        }
        try {
            List<KnowledgeBase> kbs = kbService.kbList(courseId);
            if (kbs.isEmpty()) {
                sendTo(session, Map.of("type", "error", "message", "本课程还没有知识库资料，请先在「文档管理」上传"));
                aiBusyCourses.remove(courseId);
                return;
            }
            HallMessage q = persistMessage(user.tenantId(), user.id(), courseId, HallMessage.ROLE_USER, null, content);
            broadcast(chatFrame(q, nickname(user), null), audience(courseId));
            broadcast(Map.of("type", "ai_start", "courseId", courseId), audience(courseId));
            streamAssistantAnswer(courseId, user, content, kbs);
        } catch (Exception e) {
            aiBusyCourses.remove(courseId);
            log.error("频道助教请求失败 courseId={}: {}", courseId, e.getMessage(), e);
            sendTo(session, Map.of("type", "error", "message", "助教暂时不可用，请稍后再试"));
        }
    }

    /**
     * 助教 RAG 流式回答（回调运行在 ai-stream 守护线程）。
     * 检索锚点 KB_ID 取课程最新知识库（Python 侧 rag_qa 必须有 kbId 才触发检索），KB_IDS 展开全部知识库；
     * SESSION_ID=hall-course-{id} 使同一课程共享一份助教会话记忆。
     */
    private void streamAssistantAnswer(Long courseId, AuthUser asker, String question, List<KnowledgeBase> kbs) {
        String kbIds = kbs.stream().map(kb -> String.valueOf(kb.getId()))
                .collect(java.util.stream.Collectors.joining(","));
        KnowledgeBase anchor = kbs.get(0);
        String system = new StringBuilder()
                .append("HALL_QA\n你是课程频道里的 AI 助教，结合课程知识库资料回答同学们的提问，")
                .append("标注引用编号[1][2]等，用中文友好、简洁、准确地回答。")
                .append("\nKB_ID:").append(anchor.getId())
                .append("\nKB_NAME:").append(anchor.getName() == null ? ""
                        : anchor.getName().replaceAll("[\\r\\n]+", " ").trim())
                .append("\nKB_SCOPE:course")
                .append("\nKB_IDS:").append(kbIds)
                .append("\nCOURSE_ID:").append(courseId)
                .append("\nUSER_ID:").append(asker.id())
                .append("\nSESSION_ID:hall-course-").append(courseId)
                .toString();
        List<AIChatMessage> messages = List.of(
                new AIChatMessage("system", system),
                new AIChatMessage("user", question));

        StringBuilder acc = new StringBuilder();
        modelFactory.get().stream(messages,
                delta -> {
                    acc.append(delta);
                    broadcast(Map.of("type", "ai_delta", "courseId", courseId, "delta", delta),
                            audience(courseId));
                },
                refs -> {
                    try {
                        String sources = refs == null || refs.refs() == null ? "" : refs.refs().trim();
                        if (acc.isEmpty()) {
                            broadcast(Map.of("type", "ai_error", "courseId", courseId,
                                    "message", "助教这次没能给出回答，请换个问法试试"), audience(courseId));
                            return;
                        }
                        HallMessage m = persistMessage(asker.tenantId(), HallMessage.AI_USER_ID, courseId,
                                HallMessage.ROLE_AI, sources.isEmpty() ? null : sources, acc.toString());
                        broadcast(chatFrame(m, AI_NICKNAME, "ai"), audience(courseId));
                    } finally {
                        aiBusyCourses.remove(courseId);
                    }
                },
                e -> {
                    aiBusyCourses.remove(courseId);
                    log.error("频道助教生成失败 courseId={}: {}", courseId, e.getMessage());
                    broadcast(Map.of("type", "ai_error", "courseId", courseId,
                            "message", "助教这次没能给出回答，请稍后再试"), audience(courseId));
                });
    }

    private HallMessage persistMessage(Long tenantId, Long userId, Long courseId, int role, String sources, String content) {
        HallMessage m = new HallMessage();
        m.setTenantId(tenantId);
        m.setUserId(userId);
        m.setCourseId(courseId);
        m.setRole(role);
        m.setSources(sources);
        m.setContent(content);
        m.setCreatedAt(LocalDateTime.now());
        hallMessageMapper.insert(m);
        return m;
    }

    /** 广播受众：公共大厅全员；课程频道仅同课程在线者（成员集合为连接建立时的缓存）。 */
    private Predicate<AuthUser> audience(Long courseId) {
        return courseId == null ? u -> true : u -> myCourses(u.id()).contains(courseId);
    }

    /** 频道消息帧：role 非空表示 AI 助教消息（额外带 sources；userId=0、无头像）。 */
    private Map<String, Object> chatFrame(HallMessage m, String nickname, String role) {
        boolean ai = role != null;
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "chat");
        frame.put("id", m.getId());
        frame.put("userId", m.getUserId());
        frame.put("nickname", nickname);
        frame.put("avatar", ai ? "" : avatar(m.getUserId()));
        frame.put("courseId", m.getCourseId());
        if (ai) {
            frame.put("role", role);
            frame.put("sources", m.getSources() == null ? "" : m.getSources());
        }
        frame.put("content", m.getContent());
        frame.put("sentAt", m.getCreatedAt());
        return frame;
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
        frame.put("fromAvatar", avatar(user.id()));
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

    /** 头像 URL（/files/avatars/...），未设置时返回空串，前端回退昵称首字。 */
    private String avatar(Long userId) {
        User u = userMapper.selectById(userId);
        return u == null || u.getAvatar() == null ? "" : u.getAvatar();
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
        return onlineUsersFor(null);
    }

    /** 在线名单（可按课程过滤）：courseId 为空=全厅；非空=仅该课程（创建者/已加入）的在线成员。 */
    private List<Map<String, Object>> onlineUsersFor(Long courseId) {
        Map<Long, String> byUser = new LinkedHashMap<>();
        for (Map.Entry<WebSocketSession, AuthUser> e : sessions.entrySet()) {
            AuthUser u = e.getValue();
            if (courseId != null && !myCourses(u.id()).contains(courseId)) {
                continue;
            }
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
