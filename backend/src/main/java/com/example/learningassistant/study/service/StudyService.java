package com.example.learningassistant.study.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.learningassistant.course.entity.Course;
import com.example.learningassistant.course.mapper.CourseMapper;
import com.example.learningassistant.study.entity.StudyLog;
import com.example.learningassistant.study.mapper.StudyLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 学习时长统计：前端每分钟心跳上报，按（用户, 课程, 日期）聚合累加。
 * 心跳上限 5 分钟/次，防止脚本刷时长。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StudyService {

    private final StudyLogMapper studyLogMapper;
    private final CourseMapper courseMapper;

    /** 默认回溯 12 周；个人中心可查看近一年。 */
    private static final int CALENDAR_DAYS = 84;
    private static final int MAX_CALENDAR_DAYS = 366;

    public void heartbeat(Long userId, Long courseId, int minutes) {
        int delta = Math.max(0, Math.min(minutes, 5));
        if (delta == 0) {
            return;
        }
        // course_id 落库约定：0 = 未归属具体课程（唯一索引含 NULL 会失效，故用 0 占位）
        long cid = courseId == null ? 0L : courseId;
        LocalDate today = LocalDate.now();
        StudyLog logRow = studyLogMapper.selectOne(new LambdaQueryWrapper<StudyLog>()
                .eq(StudyLog::getUserId, userId)
                .eq(StudyLog::getCourseId, cid)
                .eq(StudyLog::getStudyDate, today));
        if (logRow == null) {
            logRow = new StudyLog();
            logRow.setTenantId(1L);
            logRow.setUserId(userId);
            logRow.setCourseId(cid);
            logRow.setStudyDate(today);
            logRow.setMinutes(delta);
            studyLogMapper.insert(logRow);
        } else {
            logRow.setMinutes(logRow.getMinutes() + delta);
            studyLogMapper.updateById(logRow);
        }
    }

    /**
     * 学习时长汇总：总时长、活跃天数、近 12 周热力图、分课程统计。
     */
    public Map<String, Object> summary(Long userId) {
        return summary(userId, CALENDAR_DAYS);
    }

    public Map<String, Object> summary(Long userId, int days) {
        int calendarDays = Math.max(1, Math.min(days, MAX_CALENDAR_DAYS));
        LocalDate today = LocalDate.now();
        LocalDate since = today.minusDays(calendarDays - 1L);
        List<StudyLog> logs = studyLogMapper.selectList(new LambdaQueryWrapper<StudyLog>()
                .eq(StudyLog::getUserId, userId)
                .ge(StudyLog::getStudyDate, since)
                .le(StudyLog::getStudyDate, today)
                .orderByAsc(StudyLog::getStudyDate));

        int totalMinutes = logs.stream().mapToInt(StudyLog::getMinutes).sum();
        long activeDays = logs.stream().map(StudyLog::getStudyDate).distinct().count();

        // 热力图：按请求范围补齐日期（无记录填 0）
        Map<LocalDate, Integer> byDate = logs.stream().collect(Collectors.groupingBy(
                StudyLog::getStudyDate, Collectors.summingInt(StudyLog::getMinutes)));
        List<Map<String, Object>> calendar = new ArrayList<>();
        for (int i = 0; i < calendarDays; i++) {
            LocalDate d = since.plusDays(i);
            Map<String, Object> cell = new LinkedHashMap<>();
            cell.put("date", d.toString());
            cell.put("minutes", byDate.getOrDefault(d, 0));
            calendar.add(cell);
        }

        // 分课程统计（courseId=0 表示未归属课程）
        Map<Long, Integer> byCourse = logs.stream()
                .filter(l -> l.getCourseId() != null && l.getCourseId() != 0)
                .collect(Collectors.groupingBy(StudyLog::getCourseId,
                        LinkedHashMap::new, Collectors.summingInt(StudyLog::getMinutes)));
        Map<Long, String> names = byCourse.isEmpty() ? Map.of()
                : courseMapper.selectBatchIds(byCourse.keySet()).stream()
                        .collect(Collectors.toMap(Course::getId, Course::getName));
        List<Map<String, Object>> courseStats = byCourse.entrySet().stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("courseId", e.getKey());
            m.put("courseName", names.getOrDefault(e.getKey(), "未知课程"));
            m.put("minutes", e.getValue());
            return m;
        }).collect(Collectors.toList());
        int uncategorized = logs.stream()
                .filter(l -> l.getCourseId() == null || l.getCourseId() == 0)
                .mapToInt(StudyLog::getMinutes).sum();
        if (uncategorized > 0) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("courseId", 0);
            m.put("courseName", "未归属课程");
            m.put("minutes", uncategorized);
            courseStats.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalMinutes", totalMinutes);
        result.put("activeDays", activeDays);
        result.put("calendar", calendar);
        result.put("byCourse", courseStats);
        return result;
    }
}
