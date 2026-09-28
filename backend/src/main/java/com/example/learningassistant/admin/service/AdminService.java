package com.example.learningassistant.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.learningassistant.ai.PythonRagClient;
import com.example.learningassistant.common.BizException;
import com.example.learningassistant.practice.entity.Practice;
import com.example.learningassistant.practice.mapper.PracticeMapper;
import com.example.learningassistant.study.entity.StudyLog;
import com.example.learningassistant.study.mapper.StudyLogMapper;
import com.example.learningassistant.user.entity.User;
import com.example.learningassistant.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 管理看板服务：用户规模、活跃度（学习心跳）与 LLM 调用量/token 趋势的聚合。
 * 访问门禁：app.admin.user-ids 白名单（默认用户 1），非管理员一律拒绝。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    private static final int TREND_DAYS = 14;

    @Value("${app.admin.usernames:admin}")
    private String adminUsernames;

    private final UserMapper userMapper;
    private final StudyLogMapper studyLogMapper;
    private final PracticeMapper practiceMapper;
    private final PythonRagClient ragClient;

    public boolean isAdmin(String username) {
        if (username == null || adminUsernames == null || adminUsernames.isBlank()) {
            return false;
        }
        return Arrays.stream(adminUsernames.split(","))
                .map(String::trim)
                .anyMatch(s -> s.equalsIgnoreCase(username));
    }

    /**
     * 看板聚合：用户规模/注册趋势、活跃度（学习心跳 DAU/时长）、LLM 调用与 token 趋势。
     * LLM 数据来自 ai-service /ai/stats（JSONL 观测），不可达时该块降级为 null。
     */
    public Map<String, Object> dashboard(String viewerName) {
        if (!isAdmin(viewerName)) {
            throw new BizException("无权访问管理看板");
        }
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(TREND_DAYS - 1L);
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(today); d = d.plusDays(1)) {
            dates.add(d);
        }

        // ===== 用户 =====
        long totalUsers = userMapper.selectCount(null);
        long newToday = userMapper.selectCount(new QueryWrapper<User>()
                .ge("created_at", today.atStartOfDay()));
        long new7d = userMapper.selectCount(new QueryWrapper<User>()
                .ge("created_at", today.minusDays(6).atStartOfDay()));
        Map<String, Long> regByDate = userMapper.selectMaps(new QueryWrapper<User>()
                        .select("DATE(created_at) AS d", "COUNT(*) AS c")
                        .ge("created_at", start.atStartOfDay())
                        .groupBy("DATE(created_at)"))
                .stream()
                .collect(Collectors.toMap(m -> String.valueOf(m.get("d")),
                        m -> ((Number) m.get("c")).longValue(), (a, b) -> a));

        // ===== 活跃（学习心跳）=====
        List<Map<String, Object>> activeRows = studyLogMapper.selectMaps(new QueryWrapper<StudyLog>()
                .select("study_date AS d", "COUNT(DISTINCT user_id) AS dau", "IFNULL(SUM(minutes), 0) AS minutes")
                .ge("study_date", start)
                .groupBy("study_date"));
        Map<String, Map<String, Long>> activeByDate = activeRows.stream()
                .collect(Collectors.toMap(m -> String.valueOf(m.get("d")),
                        m -> Map.of("dau", ((Number) m.getOrDefault("dau", 0)).longValue(),
                                "minutes", ((Number) m.getOrDefault("minutes", 0)).longValue()),
                        (a, b) -> a));
        long dauToday = activeByDate.getOrDefault(today.toString(), Map.of())
                .getOrDefault("dau", 0L);

        // ===== LLM（ai-service 观测，不可达降级）=====
        Map<String, Object> llm = ragClient.aiStats(TREND_DAYS);
        Map<String, Object> llmToday = null;
        Map<String, Map<String, Object>> llmDaily = new LinkedHashMap<>();
        List<Map<String, Object>> byScene = new ArrayList<>();
        if (llm != null) {
            Object daily = llm.get("daily");
            if (daily instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        String date = String.valueOf(m.get("date"));
                        long calls = m.get("calls") instanceof Number nc ? nc.longValue() : 0;
                        long tokens = m.get("tokens") instanceof Number nt ? nt.longValue() : 0;
                        Map<String, Object> v = new LinkedHashMap<>();
                        v.put("calls", calls);
                        v.put("tokens", tokens);
                        llmDaily.put(date, v);
                    }
                }
            }
            llmToday = llmDaily.get(today.toString());
            Object scenes = llm.get("byScene");
            if (scenes instanceof Map<?, ?> sm) {
                for (Map.Entry<?, ?> e : sm.entrySet()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("scene", e.getKey());
                    if (e.getValue() instanceof Map<?, ?> v) {
                        row.put("calls", v.get("calls"));
                        row.put("failures", v.get("failures"));
                        row.put("avgMs", v.get("avgMs"));
                        row.put("tokens", v.get("tokens"));
                    }
                    byScene.add(row);
                }
            }
        }

        // ===== 14 天对齐序列（缺日期补 0，前端直接画图）=====
        List<Map<String, Object>> series = new ArrayList<>();
        for (LocalDate d : dates) {
            String key = d.toString();
            Map<String, Long> act = activeByDate.getOrDefault(key, Map.of());
            Map<String, Object> ld = llmDaily.get(key);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", key);
            row.put("registrations", regByDate.getOrDefault(key, 0L));
            row.put("dau", act.getOrDefault("dau", 0L));
            row.put("minutes", act.getOrDefault("minutes", 0L));
            row.put("llmCalls", ld == null ? 0 : ld.get("calls"));
            row.put("llmTokens", ld == null ? 0 : ld.get("tokens"));
            series.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("users", Map.of(
                "total", totalUsers,
                "newToday", newToday,
                "new7d", new7d));
        result.put("dauToday", dauToday);
        result.put("llmTodayCalls", llmToday == null ? null : llmToday.get("calls"));
        result.put("llmTodayTokens", llmToday == null ? null : llmToday.get("tokens"));
        result.put("llmAvailable", llm != null);
        result.put("byScene", byScene);
        result.put("series", series);
        return result;
    }
    /**
     * 用户列表（管理看板）：关键字匹配用户名/昵称/邮箱，分页返回，
     * 附带每人的练习次数与最近学习日期（来自练习与心跳数据）。
     */
    public Map<String, Object> users(String keyword, int page, int size) {
        int p = Math.max(page, 1);
        int sz = Math.min(Math.max(size, 1), 50);

        boolean hasKw = keyword != null && !keyword.isBlank();
        String kw = hasKw ? keyword.trim() : null;
        // 计数与列表分开建 wrapper：selectCount 与 .select() 列共用会产生非法 SQL
        long total = userMapper.selectCount(new QueryWrapper<User>()
                .and(hasKw, w -> w.like("username", kw).or().like("nickname", kw).or().like("email", kw)));
        List<User> rows = userMapper.selectList(new QueryWrapper<User>()
                .select("id", "username", "nickname", "email", "avatar", "created_at")
                .and(hasKw, w -> w.like("username", kw).or().like("nickname", kw).or().like("email", kw))
                .orderByDesc("created_at")
                .last("LIMIT " + sz + " OFFSET " + (long) (p - 1) * sz));

        // 练习次数 / 最近学习日期：两条分组查询汇总成映射，避免逐用户查库
        Map<Long, Long> practiceCounts = practiceMapper.selectMaps(new QueryWrapper<Practice>()
                        .select("user_id AS uid", "COUNT(*) AS c")
                        .groupBy("user_id"))
                .stream()
                .collect(Collectors.toMap(m -> ((Number) m.get("uid")).longValue(),
                        m -> ((Number) m.get("c")).longValue(), (a, b) -> a));
        Map<Long, String> lastStudy = studyLogMapper.selectMaps(new QueryWrapper<StudyLog>()
                        .select("user_id AS uid", "MAX(study_date) AS last_date")
                        .groupBy("user_id"))
                .stream()
                .collect(Collectors.toMap(m -> ((Number) m.get("uid")).longValue(),
                        m -> String.valueOf(m.get("last_date")), (a, b) -> a));

        List<Map<String, Object>> records = new ArrayList<>();
        for (User u : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("nickname", u.getNickname());
            m.put("email", u.getEmail());
            m.put("avatar", u.getAvatar() == null ? "" : u.getAvatar());
            m.put("createdAt", u.getCreatedAt() == null ? null : u.getCreatedAt().toString());
            m.put("practiceCount", practiceCounts.getOrDefault(u.getId(), 0L));
            m.put("lastStudy", lastStudy.get(u.getId()));
            m.put("admin", isAdmin(u.getUsername()));
            records.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", total);
        result.put("page", p);
        result.put("size", sz);
        return result;
    }
}