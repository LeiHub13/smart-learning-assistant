package com.example.learningassistant.favorite.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.learningassistant.course.entity.Course;
import com.example.learningassistant.course.mapper.CourseMapper;
import com.example.learningassistant.exam.entity.ExamAnswer;
import com.example.learningassistant.exam.entity.ExamRecord;
import com.example.learningassistant.exam.mapper.ExamAnswerMapper;
import com.example.learningassistant.exam.mapper.ExamRecordMapper;
import com.example.learningassistant.favorite.entity.Favorite;
import com.example.learningassistant.favorite.mapper.FavoriteMapper;
import com.example.learningassistant.practice.entity.Practice;
import com.example.learningassistant.practice.entity.PracticeQuestion;
import com.example.learningassistant.practice.entity.Question;
import com.example.learningassistant.practice.mapper.PracticeMapper;
import com.example.learningassistant.practice.mapper.PracticeQuestionMapper;
import com.example.learningassistant.practice.mapper.QuestionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
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
    private final PracticeMapper practiceMapper;
    private final PracticeQuestionMapper pqMapper;
    private final ExamRecordMapper examRecordMapper;
    private final ExamAnswerMapper examAnswerMapper;

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

    /** 用户在某课程下的收藏题目 id 列表（courseId 为空 = 全部课程）。 */
    public List<Long> favoriteQuestionIds(Long userId, Long courseId) {
        List<Favorite> favs = favoriteMapper.selectList(new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getUserId, userId));
        if (favs.isEmpty()) {
            return List.of();
        }
        List<Long> ids = favs.stream().map(Favorite::getQuestionId).distinct().toList();
        LambdaQueryWrapper<Question> wrapper = new LambdaQueryWrapper<Question>()
                .in(Question::getId, ids);
        if (courseId != null) {
            wrapper.eq(Question::getCourseId, courseId);
        }
        return questionMapper.selectList(wrapper).stream().map(Question::getId).toList();
    }

    /** 收藏数量（提示角标）：count=当前课程收藏数，total=全部课程收藏数。 */
    public Map<String, Long> counts(Long userId, Long courseId) {
        long total = favoriteQuestionIds(userId, null).size();
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("total", total);
        m.put("count", courseId == null ? total : favoriteQuestionIds(userId, courseId).size());
        return m;
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
        Map<Long, AnswerInfo> answers = questions.isEmpty() ? Map.of()
                : latestAnswers(userId, questions.stream().map(Question::getId).toList());

        return questions.stream().map(q -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("questionId", q.getId());
            m.put("courseId", q.getCourseId());
            m.put("courseName", names.getOrDefault(q.getCourseId(), ""));
            m.put("type", q.getType());
            m.put("stem", q.getStem());
            m.put("kpName", q.getKpName());
            m.put("difficulty", q.getDifficulty());
            m.put("options", q.getOptions());
            m.put("answer", q.getAnswer());
            m.put("analysis", q.getAnalysis());
            // 用户最近一次作答（可能为 null：收藏但从未做过），供前端红绿对比
            AnswerInfo ai = answers.get(q.getId());
            m.put("lastAnswer", ai == null ? null : ai.answer());
            m.put("lastCorrect", ai == null ? null : ai.correct());
            return m;
        }).toList();
    }

    /**
     * 用户对指定题目的最近一次作答（练习 + 考试取更晚的一次）。
     * 收藏的题可能从未作答，故仅在存在记录时返回。
     */
    private Map<Long, AnswerInfo> latestAnswers(Long userId, Collection<Long> questionIds) {
        Map<Long, LocalDateTime> at = new LinkedHashMap<>();
        Map<Long, String> ans = new LinkedHashMap<>();
        Map<Long, Boolean> ok = new LinkedHashMap<>();

        // 练习作答
        List<Practice> practices = practiceMapper.selectList(new LambdaQueryWrapper<Practice>()
                .eq(Practice::getUserId, userId));
        if (!practices.isEmpty()) {
            Map<Long, LocalDateTime> pt = new LinkedHashMap<>();
            for (Practice p : practices) {
                pt.put(p.getId(), p.getCreatedAt() == null ? LocalDateTime.MIN : p.getCreatedAt());
            }
            for (PracticeQuestion pq : pqMapper.selectList(new LambdaQueryWrapper<PracticeQuestion>()
                    .in(PracticeQuestion::getPracticeId, pt.keySet())
                    .in(PracticeQuestion::getQuestionId, questionIds))) {
                mergeLatest(at, ans, ok, pq.getQuestionId(), pt.get(pq.getPracticeId()),
                        pq.getUserAnswer(), pq.getCorrect());
            }
        }

        // 考试作答
        List<ExamRecord> records = examRecordMapper.selectList(new LambdaQueryWrapper<ExamRecord>()
                .eq(ExamRecord::getUserId, userId)
                .isNotNull(ExamRecord::getSubmittedAt));
        if (!records.isEmpty()) {
            Map<Long, LocalDateTime> rt = new LinkedHashMap<>();
            for (ExamRecord r : records) {
                rt.put(r.getId(), r.getSubmittedAt() == null ? LocalDateTime.MIN : r.getSubmittedAt());
            }
            for (ExamAnswer ea : examAnswerMapper.selectList(new LambdaQueryWrapper<ExamAnswer>()
                    .in(ExamAnswer::getRecordId, rt.keySet())
                    .in(ExamAnswer::getQuestionId, questionIds))) {
                mergeLatest(at, ans, ok, ea.getQuestionId(), rt.get(ea.getRecordId()),
                        ea.getUserAnswer(), ea.getCorrect());
            }
        }

        Map<Long, AnswerInfo> out = new LinkedHashMap<>();
        at.forEach((qid, t) -> out.put(qid, new AnswerInfo(ans.get(qid), ok.get(qid))));
        return out;
    }

    private void mergeLatest(Map<Long, LocalDateTime> at, Map<Long, String> ans, Map<Long, Boolean> ok,
                             Long questionId, LocalDateTime when, String answer, Boolean correct) {
        if (questionId == null) {
            return;
        }
        LocalDateTime cur = at.get(questionId);
        if (cur == null || (when != null && when.isAfter(cur))) {
            at.put(questionId, when == null ? LocalDateTime.MIN : when);
            ans.put(questionId, answer);
            ok.put(questionId, correct);
        }
    }

    /** 一次作答的聚合：作答内容与是否正确。 */
    private record AnswerInfo(String answer, Boolean correct) {}

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
