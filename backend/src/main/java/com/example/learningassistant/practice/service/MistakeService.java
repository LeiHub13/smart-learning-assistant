package com.example.learningassistant.practice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.learningassistant.exam.entity.ExamAnswer;
import com.example.learningassistant.exam.entity.ExamRecord;
import com.example.learningassistant.exam.mapper.ExamAnswerMapper;
import com.example.learningassistant.exam.mapper.ExamRecordMapper;
import com.example.learningassistant.practice.entity.Practice;
import com.example.learningassistant.practice.entity.PracticeQuestion;
import com.example.learningassistant.practice.entity.Question;
import com.example.learningassistant.practice.mapper.PracticeMapper;
import com.example.learningassistant.practice.mapper.PracticeQuestionMapper;
import com.example.learningassistant.practice.mapper.QuestionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 错题本：归集练习/考试中最近一次作答仍为错误的题目。
 * 查询式实现（无新表）——以"该题最近一次作答是否正确"为准，重练答对后自动出本。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MistakeService {

    private final PracticeMapper practiceMapper;
    private final PracticeQuestionMapper pqMapper;
    private final ExamRecordMapper examRecordMapper;
    private final ExamAnswerMapper examAnswerMapper;
    private final QuestionMapper questionMapper;
    private final com.example.learningassistant.exam.mapper.ExamMapper examMapper;

    /**
     * 错题分页：records（question + lastWrongAt + lastWrongAnswer + wrongCount）+ total，按最近答错时间倒序。
     */
    public Map<String, Object> page(Long userId, Long courseId, int page, int size) {
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 50);
        Map<Long, WrongInfo> wrongs = latestWrong(userId, courseId);
        Map<Long, Integer> counts = wrongCounts(userId, courseId);

        List<Long> ids = new ArrayList<>(wrongs.keySet());
        int total = ids.size();
        int from = Math.min((p - 1) * s, total);
        int to = Math.min(from + s, total);
        List<Map<String, Object>> records = new ArrayList<>();
        if (from < to) {
            List<Question> qs = questionMapper.selectList(new LambdaQueryWrapper<Question>()
                    .in(Question::getId, ids.subList(from, to)));
            Map<Long, Question> byId = qs.stream().collect(java.util.stream.Collectors
                    .toMap(Question::getId, q -> q));
            for (Long qid : ids.subList(from, to)) {
                Question q = byId.get(qid);
                if (q == null) {
                    continue; // 题目可能已被题库删除
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("question", q);
                m.put("lastWrongAt", wrongs.get(qid).at());
                m.put("lastWrongAnswer", wrongs.get(qid).answer());
                m.put("wrongCount", counts.getOrDefault(qid, 1));
                records.add(m);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", total);
        return result;
    }

    /** 错题 id 集（按最近答错时间倒序），供错题重练抽题。 */
    public List<Long> mistakeIds(Long userId, Long courseId) {
        return new ArrayList<>(latestWrong(userId, courseId).keySet());
    }

    /** 每题最近一次作答时间、答案与对错；courseId 为空聚合全部课程。 */
    private Map<Long, WrongInfo> latestWrong(Long userId, Long courseId) {
        Map<Long, LocalDateTime> latest = new LinkedHashMap<>();
        Map<Long, Boolean> latestCorrect = new LinkedHashMap<>();
        Map<Long, String> latestAnswer = new LinkedHashMap<>();

        // 练习作答：practice 上的 createdAt 作为作答时间
        List<Practice> practices = practiceMapper.selectList(new LambdaQueryWrapper<Practice>()
                .eq(Practice::getUserId, userId)
                .eq(courseId != null, Practice::getCourseId, courseId));
        if (!practices.isEmpty()) {
            Map<Long, LocalDateTime> pt = new LinkedHashMap<>();
            for (Practice p : practices) {
                pt.put(p.getId(), p.getCreatedAt() == null ? LocalDateTime.MIN : p.getCreatedAt());
            }
            for (PracticeQuestion pq : pqMapper.selectList(new LambdaQueryWrapper<PracticeQuestion>()
                    .in(PracticeQuestion::getPracticeId, pt.keySet()))) {
                merge(latest, latestCorrect, latestAnswer, pq.getQuestionId(), pt.get(pq.getPracticeId()), pq.getUserAnswer(), pq.getCorrect());
            }
        }

        // 考试作答：record 无 courseId，经 exam 转换；submittedAt 作为作答时间
        List<ExamRecord> records = examRecordMapper.selectList(new LambdaQueryWrapper<ExamRecord>()
                .eq(ExamRecord::getUserId, userId)
                .isNotNull(ExamRecord::getSubmittedAt));
        if (!records.isEmpty()) {
            Map<Long, LocalDateTime> rt = new LinkedHashMap<>();
            for (ExamRecord r : records) {
                rt.put(r.getId(), r.getSubmittedAt() == null ? LocalDateTime.MIN : r.getSubmittedAt());
            }
            List<ExamAnswer> answers = examAnswerMapper.selectList(new LambdaQueryWrapper<ExamAnswer>()
                    .in(ExamAnswer::getRecordId, rt.keySet()));
            if (courseId != null && !answers.isEmpty()) {
                var courseRecordIds = records.stream()
                        .filter(r -> courseId.equals(examCourseId(r.getExamId())))
                        .map(ExamRecord::getId)
                        .collect(java.util.stream.Collectors.toSet());
                answers = answers.stream().filter(a -> courseRecordIds.contains(a.getRecordId())).toList();
            }
            for (ExamAnswer ea : answers) {
                merge(latest, latestCorrect, latestAnswer, ea.getQuestionId(), rt.get(ea.getRecordId()), ea.getUserAnswer(), ea.getCorrect());
            }
        }

        Map<Long, WrongInfo> wrong = new LinkedHashMap<>();
        latest.entrySet().stream()
                .filter(e -> !Boolean.TRUE.equals(latestCorrect.get(e.getKey())))
                .sorted(Map.Entry.<Long, LocalDateTime>comparingByValue(Comparator.reverseOrder()))
                .forEach(e -> wrong.put(e.getKey(), new WrongInfo(e.getValue(), latestAnswer.get(e.getKey()))));
        return wrong;
    }

    /** 一次错题的聚合信息：最近答错时间 + 当时的作答内容。 */
    private record WrongInfo(LocalDateTime at, String answer) {}

    /** 累计答错次数（练习 + 考试）。 */
    private Map<Long, Integer> wrongCounts(Long userId, Long courseId) {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        List<Practice> practices = practiceMapper.selectList(new LambdaQueryWrapper<Practice>()
                .eq(Practice::getUserId, userId)
                .eq(courseId != null, Practice::getCourseId, courseId));
        if (!practices.isEmpty()) {
            for (PracticeQuestion pq : pqMapper.selectList(new LambdaQueryWrapper<PracticeQuestion>()
                    .in(PracticeQuestion::getPracticeId, practices.stream().map(Practice::getId).toList()))) {
                if (!Boolean.TRUE.equals(pq.getCorrect()) && pq.getQuestionId() != null) {
                    counts.merge(pq.getQuestionId(), 1, Integer::sum);
                }
            }
        }
        List<ExamRecord> records = examRecordMapper.selectList(new LambdaQueryWrapper<ExamRecord>()
                .eq(ExamRecord::getUserId, userId)
                .isNotNull(ExamRecord::getSubmittedAt));
        if (!records.isEmpty()) {
            for (ExamAnswer ea : examAnswerMapper.selectList(new LambdaQueryWrapper<ExamAnswer>()
                    .in(ExamAnswer::getRecordId, records.stream().map(ExamRecord::getId).toList()))) {
                if (!Boolean.TRUE.equals(ea.getCorrect()) && ea.getQuestionId() != null) {
                    counts.merge(ea.getQuestionId(), 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    private void merge(Map<Long, LocalDateTime> latest, Map<Long, Boolean> latestCorrect,
                       Map<Long, String> latestAnswer,
                       Long questionId, LocalDateTime at, String answer, Boolean correct) {
        if (questionId == null) {
            return;
        }
        LocalDateTime cur = latest.get(questionId);
        if (cur == null || at.isAfter(cur)) {
            latest.put(questionId, at);
            latestCorrect.put(questionId, Boolean.TRUE.equals(correct));
            latestAnswer.put(questionId, answer);
        }
    }

    /** examId -> courseId。 */
    private Long examCourseId(Long examId) {
        var e = examMapper.selectById(examId);
        return e == null ? null : e.getCourseId();
    }
}
