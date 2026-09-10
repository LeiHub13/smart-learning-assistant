package com.example.learningassistant.exam.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.learningassistant.common.BizException;
import com.example.learningassistant.course.entity.Course;
import com.example.learningassistant.course.mapper.CourseMapper;
import com.example.learningassistant.exam.entity.Exam;
import com.example.learningassistant.exam.entity.ExamAnswer;
import com.example.learningassistant.exam.entity.ExamQuestion;
import com.example.learningassistant.exam.entity.ExamRecord;
import com.example.learningassistant.exam.mapper.ExamAnswerMapper;
import com.example.learningassistant.exam.mapper.ExamMapper;
import com.example.learningassistant.exam.mapper.ExamQuestionMapper;
import com.example.learningassistant.exam.mapper.ExamRecordMapper;
import com.example.learningassistant.practice.entity.Question;
import com.example.learningassistant.practice.mapper.QuestionMapper;
import com.example.learningassistant.practice.service.GradingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 考试中心：组卷（手动选题/课程内随机抽题）→ 开考 → 限时作答 → 提交判分 → 成绩单。
 * 判分与掌握度更新复用 GradingService，与题库练习口径一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExamService {

    private final ExamMapper examMapper;
    private final ExamQuestionMapper examQuestionMapper;
    private final ExamRecordMapper examRecordMapper;
    private final ExamAnswerMapper examAnswerMapper;
    private final QuestionMapper questionMapper;
    private final CourseMapper courseMapper;
    private final GradingService gradingService;

    /**
     * 手动组卷：明确指定题目清单。
     */
    @Transactional
    public Exam compose(Long userId, Long courseId, String title, Integer durationMin, List<Long> questionIds) {
        if (title == null || title.isBlank()) {
            throw new BizException("考试标题不能为空");
        }
        if (questionIds == null || questionIds.isEmpty()) {
            throw new BizException("请至少选择一道题目");
        }
        if (durationMin != null && (durationMin < 5 || durationMin > 180)) {
            throw new BizException("考试时长需在 5~180 分钟之间");
        }
        List<Question> questions = questionMapper.selectBatchIds(questionIds);
        if (questions.size() != questionIds.size()) {
            throw new BizException("存在无效题目，请重新选择");
        }
        for (Question q : questions) {
            if (!courseId.equals(q.getCourseId())) {
                throw new BizException("题目不属于当前课程，请重新选择");
            }
        }
        Course course = courseMapper.selectById(courseId);
        if (course == null) {
            throw new BizException("课程不存在");
        }

        Exam exam = new Exam();
        exam.setTenantId(1L);
        exam.setUserId(userId);
        exam.setCourseId(courseId);
        exam.setTitle(title.trim());
        exam.setDurationMin(durationMin);
        exam.setTotalScore(questionIds.size() * GradingService.FULL_SCORE_PER_QUESTION);
        exam.setCreatedAt(LocalDateTime.now());
        examMapper.insert(exam);

        insertExamQuestions(exam.getId(), questionIds);
        return exam;
    }

    /**
     * 随机组卷：课程内随机抽 count 题（每题 10 分）。
     */
    @Transactional
    public Exam composeAuto(Long userId, Long courseId, String title, Integer durationMin, int count) {
        if (count < 1 || count > 20) {
            throw new BizException("题目数量需在 1~20 之间");
        }
        List<Question> all = questionMapper.selectList(new LambdaQueryWrapper<Question>()
                .eq(Question::getCourseId, courseId));
        if (all.isEmpty()) {
            throw new BizException("该课程暂无题目，请先为课程生成题库");
        }
        List<Long> ids = new ArrayList<>(all.stream().map(Question::getId).toList());
        java.util.Collections.shuffle(ids);
        if (ids.size() > count) {
            ids = ids.subList(0, count);
        }
        return compose(userId, courseId, title, durationMin, ids);
    }

    private void insertExamQuestions(Long examId, List<Long> questionIds) {
        int sortNo = 1;
        for (Long qid : questionIds) {
            ExamQuestion eq = new ExamQuestion();
            eq.setExamId(examId);
            eq.setQuestionId(qid);
            eq.setScore(GradingService.FULL_SCORE_PER_QUESTION);
            eq.setSortNo(sortNo++);
            examQuestionMapper.insert(eq);
        }
    }

    /** 课程下的考试列表（含我的最好成绩）。 */
    public List<Map<String, Object>> list(Long courseId, Long userId) {
        LambdaQueryWrapper<Exam> wrapper = new LambdaQueryWrapper<Exam>();
        if (courseId != null) {
            wrapper.eq(Exam::getCourseId, courseId);
        }
        wrapper.orderByDesc(Exam::getCreatedAt);
        List<Exam> exams = examMapper.selectList(wrapper);

        Map<Long, String> courseNames = exams.isEmpty() ? Map.of()
                : courseMapper.selectBatchIds(exams.stream().map(Exam::getCourseId).distinct().toList()).stream()
                        .collect(java.util.stream.Collectors.toMap(Course::getId, Course::getName));

        return exams.stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("title", e.getTitle());
            m.put("courseId", e.getCourseId());
            m.put("courseName", courseNames.getOrDefault(e.getCourseId(), ""));
            m.put("durationMin", e.getDurationMin());
            m.put("totalScore", e.getTotalScore());
            m.put("createdAt", e.getCreatedAt());
            m.put("owner", e.getUserId().equals(userId));
            // 我的最好成绩
            List<ExamRecord> mine = examRecordMapper.selectList(new LambdaQueryWrapper<ExamRecord>()
                    .eq(ExamRecord::getExamId, e.getId())
                    .eq(ExamRecord::getUserId, userId)
                    .isNotNull(ExamRecord::getSubmittedAt));
            m.put("attemptCount", mine.size());
            m.put("myBestScore", mine.stream().mapToInt(ExamRecord::getScore).max().orElse(-1));
            return m;
        }).toList();
    }

    /** 题库浏览（组卷选题用，不下发答案）。 */
    public List<Map<String, Object>> questionBank(Long courseId) {
        List<Question> qs = questionMapper.selectList(new LambdaQueryWrapper<Question>()
                .eq(Question::getCourseId, courseId)
                .orderByAsc(Question::getId));
        return qs.stream().map(q -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", q.getId());
            m.put("type", q.getType());
            m.put("stem", q.getStem());
            m.put("kpName", q.getKpName());
            m.put("difficulty", q.getDifficulty());
            return m;
        }).toList();
    }

    /** 考试详情（答题页数据，题目不含答案）。 */
    public Map<String, Object> detail(Long examId) {
        Exam exam = requireExam(examId);
        List<ExamQuestion> eqs = examQuestionMapper.selectList(new LambdaQueryWrapper<ExamQuestion>()
                .eq(ExamQuestion::getExamId, examId)
                .orderByAsc(ExamQuestion::getSortNo));
        List<Map<String, Object>> questions = eqs.stream().map(eq -> {
            Question q = questionMapper.selectById(eq.getQuestionId());
            Map<String, Object> m = new LinkedHashMap<>();
            if (q != null) {
                m.put("id", q.getId());
                m.put("type", q.getType());
                m.put("stem", q.getStem());
                m.put("options", q.getOptions());
                m.put("kpName", q.getKpName());
            }
            m.put("score", eq.getScore());
            return m;
        }).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exam", exam);
        result.put("questions", questions);
        return result;
    }

    /** 开考：创建作答记录（限时考试计算截止时间）。 */
    public ExamRecord start(Long userId, Long examId) {
        Exam exam = requireExam(examId);
        // 存在未提交的进行中记录则直接复用（断线恢复）
        ExamRecord ongoing = examRecordMapper.selectOne(new LambdaQueryWrapper<ExamRecord>()
                .eq(ExamRecord::getExamId, examId)
                .eq(ExamRecord::getUserId, userId)
                .isNull(ExamRecord::getSubmittedAt)
                .orderByDesc(ExamRecord::getStartedAt)
                .last("LIMIT 1"));
        if (ongoing != null) {
            return ongoing;
        }
        ExamRecord record = new ExamRecord();
        record.setTenantId(1L);
        record.setExamId(examId);
        record.setUserId(userId);
        record.setStartedAt(LocalDateTime.now());
        if (exam.getDurationMin() != null) {
            record.setDeadlineAt(LocalDateTime.now().plusMinutes(exam.getDurationMin()));
        }
        record.setTotalScore(exam.getTotalScore());
        examRecordMapper.insert(record);
        return record;
    }

    /**
     * 交卷判分：归属校验 → 逐题判分（客观规则/主观 LLM）→ 更新掌握度 → 返回成绩。
     * 超时提交仍受理（避免到时未提交直接 0 分）。
     */
    @Transactional
    public Map<String, Object> submit(Long userId, Long recordId, List<Map<String, Object>> items) {
        ExamRecord record = requireOwnedRecord(recordId, userId);
        if (record.getSubmittedAt() != null) {
            throw new BizException("该场考试已交卷，不能重复提交");
        }
        Exam exam = requireExam(record.getExamId());
        if (items == null || items.isEmpty()) {
            throw new BizException("没有可提交的答案");
        }

        int total = 0;
        Map<String, int[]> kpStats = new HashMap<>();
        for (Map<String, Object> it : items) {
            Long qid = Long.valueOf(String.valueOf(it.get("questionId")));
            String userAnswer = it.get("answer") == null ? "" : String.valueOf(it.get("answer"));
            Question q = questionMapper.selectById(qid);
            if (q == null) {
                continue;
            }
            GradingService.Grade g = gradingService.grade(q, userAnswer);

            ExamAnswer ea = new ExamAnswer();
            ea.setRecordId(recordId);
            ea.setQuestionId(qid);
            ea.setUserAnswer(userAnswer);
            ea.setScore(g.score());
            ea.setCorrect(g.correct());
            ea.setReview(g.review());
            ea.setKpName(q.getKpName());
            examAnswerMapper.insert(ea);

            total += g.score();
            if (q.getKpName() != null && !q.getKpName().isBlank()) {
                int[] s = kpStats.computeIfAbsent(q.getKpName(), k -> new int[2]);
                s[0]++;
                if (g.correct()) {
                    s[1]++;
                }
            }
        }

        record.setSubmittedAt(LocalDateTime.now());
        record.setScore(total);
        record.setTotalScore(exam.getTotalScore());
        examRecordMapper.updateById(record);
        gradingService.updateMastery(userId, exam.getCourseId(), kpStats);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recordId", record.getId());
        result.put("score", total);
        result.put("totalScore", exam.getTotalScore());
        return result;
    }

    /** 成绩单（归属校验，含每题作答与解析）。 */
    public Map<String, Object> report(Long userId, Long recordId) {
        ExamRecord record = requireOwnedRecord(recordId, userId);
        Exam exam = requireExam(record.getExamId());
        List<ExamAnswer> answers = examAnswerMapper.selectList(new LambdaQueryWrapper<ExamAnswer>()
                .eq(ExamAnswer::getRecordId, recordId)
                .orderByAsc(ExamAnswer::getId));
        List<Map<String, Object>> items = answers.stream().map(ea -> {
            Question q = questionMapper.selectById(ea.getQuestionId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("answer", ea);
            if (q != null) {
                m.put("question", Map.of(
                        "id", q.getId(),
                        "type", q.getType(),
                        "stem", q.getStem(),
                        "options", q.getOptions() == null ? "" : q.getOptions(),
                        "answer", q.getAnswer() == null ? "" : q.getAnswer(),
                        "analysis", q.getAnalysis() == null ? "" : q.getAnalysis(),
                        "kpName", q.getKpName() == null ? "" : q.getKpName()));
            }
            return m;
        }).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exam", Map.of(
                "id", exam.getId(),
                "title", exam.getTitle(),
                "courseId", exam.getCourseId(),
                "durationMin", exam.getDurationMin() == null ? 0 : exam.getDurationMin()));
        result.put("record", record);
        result.put("items", items);
        return result;
    }

    /** 我在某场考试的历次成绩。 */
    public List<ExamRecord> myRecords(Long userId, Long examId) {
        return examRecordMapper.selectList(new LambdaQueryWrapper<ExamRecord>()
                .eq(ExamRecord::getExamId, examId)
                .eq(ExamRecord::getUserId, userId)
                .orderByDesc(ExamRecord::getStartedAt));
    }

    private Exam requireExam(Long examId) {
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            throw new BizException("考试不存在");
        }
        return exam;
    }

    private ExamRecord requireOwnedRecord(Long recordId, Long userId) {
        ExamRecord record = examRecordMapper.selectById(recordId);
        if (record == null || userId == null || !record.getUserId().equals(userId)) {
            throw new BizException("考试记录不存在或无权访问");
        }
        return record;
    }
}
