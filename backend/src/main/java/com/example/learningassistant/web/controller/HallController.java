package com.example.learningassistant.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.learningassistant.common.ApiResponse;
import com.example.learningassistant.course.entity.Course;
import com.example.learningassistant.course.entity.CourseUser;
import com.example.learningassistant.course.mapper.CourseMapper;
import com.example.learningassistant.course.mapper.CourseUserMapper;
import com.example.learningassistant.hall.entity.HallMessage;
import com.example.learningassistant.hall.mapper.HallMessageMapper;
import com.example.learningassistant.security.AuthUser;
import com.example.learningassistant.security.CurrentUser;
import com.example.learningassistant.user.entity.User;
import com.example.learningassistant.user.mapper.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 对话厅：频道列表、按频道的历史消息（实时部分走 WebSocket /ws/hall）。
 */
@RestController
@RequestMapping("/api/hall")
@RequiredArgsConstructor
public class HallController {

    private static final int MAX_LIMIT = 200;

    private final HallMessageMapper hallMessageMapper;
    private final UserMapper userMapper;
    private final CourseMapper courseMapper;
    private final CourseUserMapper courseUserMapper;
    private final com.example.learningassistant.note.mapper.NoteMapper noteMapper;

    /** AI 助教消息展示昵称（与 HallWebSocketHandler 保持一致） */
    private static final String AI_NICKNAME = "AI 助教";

    /**
     * 可用频道：公共大厅 + 我创建/已加入的课程（口径同「我的课程」）。
     */
    @GetMapping("/channels")
    public ApiResponse<List<Map<String, Object>>> channels(HttpServletRequest request) {
        AuthUser me = CurrentUser.get(request);
        Set<Long> courseIds = new HashSet<>();
        courseMapper.selectList(new LambdaQueryWrapper<Course>()
                        .eq(Course::getOwnerId, me.id()).select(Course::getId, Course::getName))
                .forEach(c -> courseIds.add(c.getId()));
        courseUserMapper.selectList(new LambdaQueryWrapper<CourseUser>()
                        .eq(CourseUser::getUserId, me.id()).select(CourseUser::getCourseId))
                .forEach(cu -> courseIds.add(cu.getCourseId()));

        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, Object> pub = new LinkedHashMap<>();
        pub.put("courseId", null);
        pub.put("name", "公共大厅");
        result.add(pub);
        if (!courseIds.isEmpty()) {
            courseMapper.selectBatchIds(courseIds).stream()
                    .sorted((a, b) -> Long.compare(b.getId(), a.getId()))
                    .forEach(c -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("courseId", c.getId());
                        m.put("name", c.getName());
                        result.add(m);
                    });
        }
        return ApiResponse.ok(result);
    }

    /**
     * 最近 N 条（默认 50，倒序取完反转为正序），昵称批量补齐。
     * courseId 缺省=公共大厅（course_id IS NULL）；传课程 id 则校验成员身份。
     * role=1 为 AI 助教消息（userId=0），昵称固定「AI 助教」，sources 为引用编号。
     */
    @GetMapping("/messages")
    public ApiResponse<List<HallMessageView>> messages(@RequestParam(defaultValue = "50") int limit,
                                                       @RequestParam(required = false) Long courseId,
                                                       HttpServletRequest request) {
        AuthUser me = CurrentUser.get(request);
        int n = Math.min(Math.max(limit, 1), MAX_LIMIT);
        if (courseId != null && !myCourseIds(me.id()).contains(courseId)) {
            throw new com.example.learningassistant.common.BizException("只能查看自己创建或已加入课程的历史消息");
        }
        LambdaQueryWrapper<HallMessage> qw = new LambdaQueryWrapper<HallMessage>()
                .orderByDesc(HallMessage::getId)
                .last("LIMIT " + n);
        if (courseId == null) {
            qw.isNull(HallMessage::getCourseId);
        } else {
            qw.eq(HallMessage::getCourseId, courseId);
        }
        List<HallMessage> latest = hallMessageMapper.selectList(qw);
        List<HallMessage> ordered = new ArrayList<>(latest);
        ordered.sort((a, b) -> Long.compare(a.getId(), b.getId()));

        List<Long> userIds = ordered.stream().map(HallMessage::getUserId)
                .filter(id -> id != null && id != 0L).distinct().toList();
        Map<Long, User> users = usersById(userIds);
        return ApiResponse.ok(ordered.stream()
                .map(m -> {
                    int role = m.getRole() == null ? HallMessage.ROLE_USER : m.getRole();
                    String nickname;
                    String avatar;
                    if (role == HallMessage.ROLE_AI) {
                        nickname = AI_NICKNAME;
                        avatar = "";
                    } else {
                        User u = users.get(m.getUserId());
                        nickname = u == null ? "用户" + m.getUserId() : display(u);
                        avatar = u == null || u.getAvatar() == null ? "" : u.getAvatar();
                    }
                    return new HallMessageView(m.getId(), m.getUserId(), nickname, avatar,
                            m.getCourseId(), role, m.getSources(), m.getContent(), m.getCreatedAt());
                })
                .toList());
    }

    /**
     * 学习空间：本课程已被作者共享（shared=1）的笔记，课程成员只读可见，最新更新在前。
     */
    @GetMapping("/space/notes")
    public ApiResponse<List<SpaceNoteView>> spaceNotes(@RequestParam Long courseId, HttpServletRequest request) {
        AuthUser me = CurrentUser.get(request);
        if (!myCourseIds(me.id()).contains(courseId)) {
            throw new com.example.learningassistant.common.BizException("只能查看自己创建或已加入课程的学习空间");
        }
        List<com.example.learningassistant.note.entity.Note> notes = noteMapper.selectList(
                new LambdaQueryWrapper<com.example.learningassistant.note.entity.Note>()
                        .eq(com.example.learningassistant.note.entity.Note::getCourseId, courseId)
                        .eq(com.example.learningassistant.note.entity.Note::getShared, 1)
                        .orderByDesc(com.example.learningassistant.note.entity.Note::getUpdatedAt)
                        .last("LIMIT 50"));
        Map<Long, User> users = usersById(notes.stream()
                .map(com.example.learningassistant.note.entity.Note::getUserId).distinct().toList());
        return ApiResponse.ok(notes.stream().map(n -> {
            User u = users.get(n.getUserId());
            return new SpaceNoteView(n.getId(), n.getUserId(),
                    u == null ? "用户" + n.getUserId() : display(u),
                    n.getKpName(), n.getTitle(), n.getContent(), n.getUpdatedAt());
        }).toList());
    }

    private Set<Long> myCourseIds(Long userId) {
        Set<Long> ids = new HashSet<>();
        courseMapper.selectList(new LambdaQueryWrapper<Course>()
                        .eq(Course::getOwnerId, userId).select(Course::getId))
                .forEach(c -> ids.add(c.getId()));
        courseUserMapper.selectList(new LambdaQueryWrapper<CourseUser>()
                        .eq(CourseUser::getUserId, userId).select(CourseUser::getCourseId))
                .forEach(cu -> ids.add(cu.getCourseId()));
        return ids;
    }

    private Map<Long, User> usersById(List<Long> userIds) {
        return userIds.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(userIds).stream()
                        .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
    }

    private static String display(User u) {
        return u.getNickname() == null || u.getNickname().isBlank() ? u.getUsername() : u.getNickname();
    }

    /** 历史消息视图：昵称/头像在读取时补齐，落库只存 userId；courseId 为空表示公共大厅。
     *  role 0=用户消息 1=AI 助教（userId=0，sources 为引用编号）。 */
    public record HallMessageView(Long id, Long userId, String nickname, String avatar,
                                  Long courseId, Integer role, String sources,
                                  String content, LocalDateTime createdAt) {
    }

    /** 学习空间共享笔记视图：只读展示，author 为作者昵称。 */
    public record SpaceNoteView(Long id, Long userId, String author, String kpName,
                                String title, String content, LocalDateTime updatedAt) {
    }
}
