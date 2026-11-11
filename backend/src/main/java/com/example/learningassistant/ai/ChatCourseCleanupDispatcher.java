package com.example.learningassistant.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 异步派发已提交的课程删除事件，Python 不可用时保留任务重试。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatCourseCleanupDispatcher {
    private final JdbcTemplate jdbc;
    private final PythonChatClient chat;

    @Scheduled(fixedDelay = 10000, initialDelay = 5000)
    public void dispatch() {
        for (Long courseId : jdbc.queryForList(
                "SELECT course_id FROM t_chat_course_cleanup ORDER BY created_at LIMIT 100", Long.class)) {
            try {
                chat.deleteCourseSessions(courseId);
                jdbc.update("DELETE FROM t_chat_course_cleanup WHERE course_id=?", courseId);
            } catch (RuntimeException e) {
                log.warn("课程 {} 的答疑会话清理等待重试：{}", courseId, e.getMessage());
            }
        }
    }
}
