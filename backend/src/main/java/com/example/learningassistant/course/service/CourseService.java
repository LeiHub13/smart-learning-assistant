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
}
