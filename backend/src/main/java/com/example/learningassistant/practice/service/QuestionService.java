package com.example.learningassistant.practice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.learningassistant.common.BizException;
import com.example.learningassistant.course.mapper.CourseMapper;
import com.example.learningassistant.exam.entity.ExamAnswer;
import com.example.learningassistant.exam.mapper.ExamAnswerMapper;
import com.example.learningassistant.favorite.entity.Favorite;
import com.example.learningassistant.favorite.mapper.FavoriteMapper;
import com.example.learningassistant.practice.entity.PracticeQuestion;
import com.example.learningassistant.practice.entity.Question;
import com.example.learningassistant.practice.mapper.PracticeQuestionMapper;
import com.example.learningassistant.practice.mapper.QuestionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 题库管理：分页查询、手动录入、编辑、删除。
 * 课程为全租户共享资源（无角色概念，所有用户权限一致），题目仅做存在性校验；
 * 已被练习/考试/收藏引用的题不允许删除，避免报告悬空。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuestionService {

    private final QuestionMapper questionMapper;
    private final CourseMapper courseMapper;
    private final ExamAnswerMapper examAnswerMapper;
    private final PracticeQuestionMapper practiceQuestionMapper;
    private final FavoriteMapper favoriteMapper;

    /** 题库分页：records + total，支持类型/难度/关键词（题干、知识点）过滤。 */
    public Map<String, Object> page(Long userId, Long courseId, int page, int size,
                                    String type, String difficulty, String keyword) {
        requireCourse(courseId);
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 50);
        long total = questionMapper.selectCount(baseWrapper(courseId, type, difficulty, keyword));
        List<Question> records = questionMapper.selectList(baseWrapper(courseId, type, difficulty, keyword)
                .orderByDesc(Question::getCreatedAt)
                .orderByDesc(Question::getId)
                .last("LIMIT " + s + " OFFSET " + (long) (p - 1) * s));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("records", records);
        m.put("total", total);
        return m;
    }

    /** 手动录入题目（source=手动；options 为前端序列化的 [{k,v}] JSON 串）。 */
    public Question create(Long userId, Long courseId, String type, String stem, String options,
                           String answer, String analysis, String kpName, String difficulty) {
        requireCourse(courseId);
        Question q = new Question();
        q.setCourseId(courseId);
        applyFields(q, type, stem, options, answer, analysis, kpName, difficulty);
        q.setSource("手动");
        q.setCreatedAt(LocalDateTime.now());
        questionMapper.insert(q);
        return q;
    }

    /** 编辑题目（课程成员可改）。 */
    public Question update(Long userId, Long id, String type, String stem, String options,
                           String answer, String analysis, String kpName, String difficulty) {
        Question q = requireOwnedQuestion(userId, id);
        applyFields(q, type, stem, options, answer, analysis, kpName, difficulty);
        questionMapper.updateById(q);
        return q;
    }

    /** 删除题目：已被练习/考试作答或收藏引用的题不允许删，避免报告悬空。 */
    public void delete(Long userId, Long id) {
        requireOwnedQuestion(userId, id);
        long used = examAnswerMapper.selectCount(new LambdaQueryWrapper<ExamAnswer>().eq(ExamAnswer::getQuestionId, id))
                + practiceQuestionMapper.selectCount(new LambdaQueryWrapper<PracticeQuestion>().eq(PracticeQuestion::getQuestionId, id))
                + favoriteMapper.selectCount(new LambdaQueryWrapper<Favorite>().eq(Favorite::getQuestionId, id));
        if (used > 0) {
            throw new BizException("该题已被练习/考试记录或收藏引用，无法删除");
        }
        questionMapper.deleteById(id);
    }

    private LambdaQueryWrapper<Question> baseWrapper(Long courseId, String type, String difficulty, String keyword) {
        LambdaQueryWrapper<Question> w = new LambdaQueryWrapper<Question>().eq(Question::getCourseId, courseId);
        if (type != null && !type.isBlank()) {
            w.eq(Question::getType, type);
        }
        if (difficulty != null && !difficulty.isBlank()) {
            w.eq(Question::getDifficulty, difficulty);
        }
        if (keyword != null && !keyword.isBlank()) {
            w.and(x -> x.like(Question::getStem, keyword).or().like(Question::getKpName, keyword));
        }
        return w;
    }

    private void applyFields(Question q, String type, String stem, String options,
                             String answer, String analysis, String kpName, String difficulty) {
        if (stem == null || stem.isBlank()) {
            throw new BizException("题干不能为空");
        }
        q.setType(type == null || type.isBlank() ? "单选" : type);
        q.setStem(stem.trim());
        q.setOptions(options == null || options.isBlank() ? null : options);
        q.setAnswer(answer == null ? null : answer.trim());
        q.setAnalysis(analysis);
        q.setKpName(kpName == null || kpName.isBlank() ? null : kpName.trim());
        q.setDifficulty(difficulty == null || difficulty.isBlank() ? "进阶" : difficulty);
    }

    private Question requireOwnedQuestion(Long userId, Long id) {
        Question q = questionMapper.selectById(id);
        if (q == null) {
            throw new BizException("题目不存在");
        }
        return q;
    }

    private void requireCourse(Long courseId) {
        if (courseMapper.selectById(courseId) == null) {
            throw new BizException("课程不存在");
        }
    }
}
