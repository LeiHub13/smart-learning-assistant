package com.example.learningassistant.favorite.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.learningassistant.course.entity.Course;
import com.example.learningassistant.course.mapper.CourseMapper;
import com.example.learningassistant.favorite.entity.Favorite;
import com.example.learningassistant.favorite.mapper.FavoriteMapper;
import com.example.learningassistant.practice.entity.Question;
import com.example.learningassistant.practice.mapper.QuestionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 题目收藏服务：收藏/取消（幂等切换）、收藏夹列表（含题目概览与课程名）。
 * 收藏的题目通过练习模块的 favorite 抽题参数直接重练，判分链路完全复用。
 */
@Service
@RequiredArgsConstructor
public class FavoriteService {

    private final FavoriteMapper favoriteMapper;
    private final QuestionMapper questionMapper;
    private final CourseMapper courseMapper;

    /** 切换收藏状态：已收藏则取消，未收藏则添加。返回切换后的状态。 */
    public boolean toggle(Long userId, Long questionId) {
        Favorite existing = favoriteMapper.selectOne(new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getUserId, userId)
                .eq(Favorite::getQuestionId, questionId));
        if (existing != null) {
            favoriteMapper.deleteById(existing.getId());
            return false;
        }
        Favorite f = new Favorite();
        f.setTenantId(1L);
        f.setUserId(userId);
        f.setQuestionId(questionId);
        f.setCreatedAt(LocalDateTime.now());
        favoriteMapper.insert(f);
        return true;
    }

    /** 用户在某课程下的收藏题目 id 列表。 */
    public List<Long> favoriteQuestionIds(Long userId, Long courseId) {
        List<Favorite> favs = favoriteMapper.selectList(new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getUserId, userId));
        if (favs.isEmpty()) {
            return List.of();
        }
        List<Long> ids = favs.stream().map(Favorite::getQuestionId).distinct().toList();
        // 过滤出属于该课程的收藏题
        return questionMapper.selectList(new LambdaQueryWrapper<Question>()
                .in(Question::getId, ids)
                .eq(Question::getCourseId, courseId)).stream().map(Question::getId).toList();
    }

    /** 收藏数量（提示角标）。 */
    public long count(Long userId, Long courseId) {
        return favoriteQuestionIds(userId, courseId).size();
    }

    /** 收藏夹概览（题目信息，不含答案；courseId 可选过滤）。 */
    public List<Map<String, Object>> list(Long userId, Long courseId) {
        List<Favorite> favs = favoriteMapper.selectList(new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getUserId, userId)
                .orderByDesc(Favorite::getCreatedAt));
        if (favs.isEmpty()) {
            return List.of();
        }
        List<Long> ids = favs.stream().map(Favorite::getQuestionId).toList();
        List<Question> questions = questionMapper.selectList(new LambdaQueryWrapper<Question>()
                .in(Question::getId, ids));
        if (courseId != null) {
            questions = questions.stream().filter(q -> courseId.equals(q.getCourseId())).toList();
        }
        Set<Long> courseIds = questions.stream().map(Question::getCourseId).collect(java.util.stream.Collectors.toSet());
        Map<Long, String> names = courseIds.isEmpty() ? Map.of()
                : courseMapper.selectBatchIds(courseIds).stream()
                        .collect(java.util.stream.Collectors.toMap(Course::getId, Course::getName));

        return questions.stream().map(q -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("questionId", q.getId());
            m.put("courseId", q.getCourseId());
            m.put("courseName", names.getOrDefault(q.getCourseId(), ""));
            m.put("type", q.getType());
            m.put("stem", q.getStem());
            m.put("kpName", q.getKpName());
            return m;
        }).toList();
    }

    /** 题目是否已收藏（报告页标记用）。 */
    public Set<Long> favoritedIds(Long userId, List<Long> questionIds) {
        if (questionIds == null || questionIds.isEmpty()) {
            return Set.of();
        }
        return favoriteMapper.selectList(new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getUserId, userId)
                .in(Favorite::getQuestionId, questionIds)).stream()
                .map(Favorite::getQuestionId).collect(java.util.stream.Collectors.toSet());
    }
}
