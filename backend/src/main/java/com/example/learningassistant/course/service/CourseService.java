package com.example.learningassistant.course.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.learningassistant.common.BizException;
import com.example.learningassistant.course.entity.Course;
import com.example.learningassistant.course.entity.CourseUser;
import com.example.learningassistant.course.mapper.CourseMapper;
import com.example.learningassistant.course.mapper.CourseUserMapper;
import com.example.learningassistant.kb.entity.KnowledgeBase;
import com.example.learningassistant.kb.mapper.KnowledgeBaseMapper;
import com.example.learningassistant.practice.entity.Question;
import com.example.learningassistant.practice.mapper.QuestionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 课程服务：创建课程、课程列表（含选课/统计）、选课。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CourseService {

    private final CourseMapper courseMapper;
    private final CourseUserMapper courseUserMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final QuestionMapper questionMapper;
    private final com.example.learningassistant.kb.service.KbService kbService;
    private final com.example.learningassistant.exam.mapper.ExamMapper examMapper;
    private final com.example.learningassistant.exam.mapper.ExamQuestionMapper examQuestionMapper;
    private final com.example.learningassistant.exam.mapper.ExamRecordMapper examRecordMapper;
    private final com.example.learningassistant.exam.mapper.ExamAnswerMapper examAnswerMapper;
    private final com.example.learningassistant.practice.mapper.PracticeMapper practiceMapper;
    private final com.example.learningassistant.practice.mapper.PracticeQuestionMapper practiceQuestionMapper;
    private final com.example.learningassistant.progress.mapper.KnowledgeMasteryMapper masteryMapper;
    private final com.example.learningassistant.note.mapper.NoteMapper noteMapper;
    private final com.example.learningassistant.study.mapper.StudyLogMapper studyLogMapper;
    private final com.example.learningassistant.report.mapper.ReportMapper reportMapper;
    private final com.example.learningassistant.plan.mapper.StudyPlanMapper planMapper;
    private final com.example.learningassistant.plan.mapper.PlanTaskMapper planTaskMapper;
    private final com.example.learningassistant.chat.mapper.ChatSessionMapper chatSessionMapper;
    private final com.example.learningassistant.chat.mapper.ChatMessageMapper chatMessageMapper;

    public List<Map<String, Object>> listFor(Long userId) {
        List<Course> courses = courseMapper.selectList(new LambdaQueryWrapper<Course>().orderByDesc(Course::getCreatedAt));
        List<Long> enrolledCourseIds = courseUserMapper.selectList(new LambdaQueryWrapper<CourseUser>()
                        .eq(CourseUser::getUserId, userId)).stream()
                .map(CourseUser::getCourseId).toList();
        return courses.stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getName());
            m.put("description", c.getDescription());
            m.put("ownerName", c.getOwnerName());
            m.put("ownerId", c.getOwnerId());
            m.put("enrolled", enrolledCourseIds.contains(c.getId()));
            m.put("kbCount", kbMapper.selectCount(new LambdaQueryWrapper<KnowledgeBase>()
                    .eq(KnowledgeBase::getCourseId, c.getId())).intValue());
            m.put("questionCount", questionMapper.selectCount(new LambdaQueryWrapper<Question>()
                    .eq(Question::getCourseId, c.getId())).intValue());
            return m;
        }).toList();
    }

    public Course create(String name, String description, Long ownerId, String ownerName) {
        Course c = new Course();
        c.setName(name);
        c.setDescription(description);
        c.setOwnerId(ownerId);
        c.setOwnerName(ownerName);
        c.setCreatedAt(LocalDateTime.now());
        courseMapper.insert(c);
        enroll(ownerId, c.getId());
        return c;
    }

    public void enroll(Long userId, Long courseId) {
        require(courseId);
        Long cnt = courseUserMapper.selectCount(new LambdaQueryWrapper<CourseUser>()
                .eq(CourseUser::getCourseId, courseId)
                .eq(CourseUser::getUserId, userId));
        if (cnt > 0) {
            return;
        }
        CourseUser cu = new CourseUser();
        cu.setCourseId(courseId);
        cu.setUserId(userId);
        cu.setJoinedAt(LocalDateTime.now());
        courseUserMapper.insert(cu);
    }

    public Course require(Long id) {
        Course c = courseMapper.selectById(id);
        if (c == null) {
            throw new BizException("课程不存在");
        }
        return c;
    }

    /**
     * 删除课程（仅创建者可删）：级联清理知识库（文档/分块/向量）、考试、练习、
     * 掌握度、笔记、时长、报告、计划、课程绑定的会话与选课关系。
     */
    @Transactional
    public void delete(Long userId, Long courseId) {
        Course c = require(courseId);
        if (c.getOwnerId() == null || !c.getOwnerId().equals(userId)) {
            throw new BizException("仅课程创建者可删除该课程");
        }

        // 知识库（含文档/分块/向量库清理）
        for (com.example.learningassistant.kb.entity.KnowledgeBase kb : kbMapper.selectList(
                new LambdaQueryWrapper<com.example.learningassistant.kb.entity.KnowledgeBase>()
                        .eq(com.example.learningassistant.kb.entity.KnowledgeBase::getCourseId, courseId))) {
            kbService.deleteKb(kb.getId());
        }

        // 考试（记录→答案→题目→考试）
        List<Long> examIds = examMapper.selectList(new LambdaQueryWrapper<com.example.learningassistant.exam.entity.Exam>()
                        .eq(com.example.learningassistant.exam.entity.Exam::getCourseId, courseId))
                .stream().map(com.example.learningassistant.exam.entity.Exam::getId).toList();
        if (!examIds.isEmpty()) {
            List<Long> recordIds = examRecordMapper.selectList(new LambdaQueryWrapper<com.example.learningassistant.exam.entity.ExamRecord>()
                            .in(com.example.learningassistant.exam.entity.ExamRecord::getExamId, examIds))
                    .stream().map(com.example.learningassistant.exam.entity.ExamRecord::getId).toList();
            if (!recordIds.isEmpty()) {
                examAnswerMapper.delete(new LambdaQueryWrapper<com.example.learningassistant.exam.entity.ExamAnswer>()
                        .in(com.example.learningassistant.exam.entity.ExamAnswer::getRecordId, recordIds));
            }
            examRecordMapper.delete(new LambdaQueryWrapper<com.example.learningassistant.exam.entity.ExamRecord>()
                    .in(com.example.learningassistant.exam.entity.ExamRecord::getExamId, examIds));
            examQuestionMapper.delete(new LambdaQueryWrapper<com.example.learningassistant.exam.entity.ExamQuestion>()
                    .in(com.example.learningassistant.exam.entity.ExamQuestion::getExamId, examIds));
            examMapper.deleteByIds(examIds);
        }

        // 练习
        List<Long> practiceIds = practiceMapper.selectList(new LambdaQueryWrapper<com.example.learningassistant.practice.entity.Practice>()
                        .eq(com.example.learningassistant.practice.entity.Practice::getCourseId, courseId))
                .stream().map(com.example.learningassistant.practice.entity.Practice::getId).toList();
        if (!practiceIds.isEmpty()) {
            practiceQuestionMapper.delete(new LambdaQueryWrapper<com.example.learningassistant.practice.entity.PracticeQuestion>()
                    .in(com.example.learningassistant.practice.entity.PracticeQuestion::getPracticeId, practiceIds));
            practiceMapper.deleteByIds(practiceIds);
        }

        masteryMapper.delete(new LambdaQueryWrapper<com.example.learningassistant.progress.entity.KnowledgeMastery>()
                .eq(com.example.learningassistant.progress.entity.KnowledgeMastery::getCourseId, courseId));
        noteMapper.delete(new LambdaQueryWrapper<com.example.learningassistant.note.entity.Note>()
                .eq(com.example.learningassistant.note.entity.Note::getCourseId, courseId));
        studyLogMapper.delete(new LambdaQueryWrapper<com.example.learningassistant.study.entity.StudyLog>()
                .eq(com.example.learningassistant.study.entity.StudyLog::getCourseId, courseId));
        reportMapper.delete(new LambdaQueryWrapper<com.example.learningassistant.report.entity.Report>()
                .eq(com.example.learningassistant.report.entity.Report::getCourseId, courseId));

        // 学习计划
        List<Long> planIds = planMapper.selectList(new LambdaQueryWrapper<com.example.learningassistant.plan.entity.StudyPlan>()
                        .eq(com.example.learningassistant.plan.entity.StudyPlan::getCourseId, courseId))
                .stream().map(com.example.learningassistant.plan.entity.StudyPlan::getId).toList();
        if (!planIds.isEmpty()) {
            planTaskMapper.delete(new LambdaQueryWrapper<com.example.learningassistant.plan.entity.PlanTask>()
                    .in(com.example.learningassistant.plan.entity.PlanTask::getPlanId, planIds));
            planMapper.deleteByIds(planIds);
        }

        // 课程绑定的答疑会话（自由会话 courseId 为空，保留）
        List<Long> sessionIds = chatSessionMapper.selectList(new LambdaQueryWrapper<com.example.learningassistant.chat.entity.ChatSession>()
                        .eq(com.example.learningassistant.chat.entity.ChatSession::getCourseId, courseId))
                .stream().map(com.example.learningassistant.chat.entity.ChatSession::getId).toList();
        if (!sessionIds.isEmpty()) {
            chatMessageMapper.delete(new LambdaQueryWrapper<com.example.learningassistant.chat.entity.ChatMessage>()
                    .in(com.example.learningassistant.chat.entity.ChatMessage::getSessionId, sessionIds));
            chatSessionMapper.deleteByIds(sessionIds);
        }

        courseUserMapper.delete(new LambdaQueryWrapper<com.example.learningassistant.course.entity.CourseUser>()
                .eq(com.example.learningassistant.course.entity.CourseUser::getCourseId, courseId));
        courseMapper.deleteById(courseId);
        log.info("课程 {} 已删除（级联清理完成）", courseId);
    }
}
