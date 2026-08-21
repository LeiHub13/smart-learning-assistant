package com.example.learningassistant;

import com.example.learningassistant.course.entity.Course;
import com.example.learningassistant.course.mapper.CourseMapper;
import com.example.learningassistant.user.entity.User;
import com.example.learningassistant.user.mapper.UserMapper;
import com.example.learningassistant.user.service.AuthService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 演示数据初始化：账号、课程、掌握度与练习样例（供 AnalyticsMapper 聚合查询演示）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final UserMapper userMapper;
    private final CourseMapper courseMapper;
    private final com.example.learningassistant.progress.mapper.KnowledgeMasteryMapper masteryMapper;
    private final com.example.learningassistant.practice.mapper.PracticeMapper practiceMapper;
    private final com.example.learningassistant.practice.mapper.QuestionMapper questionMapper;
    private final com.example.learningassistant.kb.service.KbService kbService;

    @Override
    public void run(String... args) {
        Long studentId = seedUser("student", "张三同学");
        seedUser("teacher", "王老师");
        seedUser("admin", "管理员");
        Long courseId = seedCourse();
        seedMasteryAndPractices(studentId, courseId);
        seedQuestions(courseId);
        seedKb(courseId);
        kbService.reindexFromChunks();
        log.info("V2 演示数据初始化完成");
    }

    private Long seedUser(String username, String nickname) {
        if (userMapper.findByUsername(username).isPresent()) {
            return userMapper.findByUsername(username).get().getId();
        }
        User u = new User();
        u.setTenantId(1L);
        u.setUsername(username);
        u.setPassword(AuthService.hash("123456"));
        u.setRole("user");
        u.setNickname(nickname);
        u.setCreatedAt(LocalDateTime.now());
        userMapper.insert(u);
        return u.getId();
    }

    private Long seedCourse() {
        Long existing = courseMapper.selectCount(null);
        if (existing != null && existing > 0) {
            return courseMapper.selectList(null).get(0).getId();
        }
        Course c = new Course();
        c.setName("Java 编程基础");
        c.setDescription("涵盖 Java 核心语法、面向对象、集合框架、多线程等知识点的入门课程。");
        c.setTeacherId(2L);
        c.setTeacherName("王老师");
        c.setCreatedAt(LocalDateTime.now());
        courseMapper.insert(c);
        return c.getId();
    }

    private void seedMasteryAndPractices(Long studentId, Long courseId) {
        // 掌握度样例：3 个知识点，掌握度依次为 40 / 65 / 90
        upsertMastery(studentId, courseId, "HashMap原理", 40, 10, 4);
        upsertMastery(studentId, courseId, "Java集合框架", 65, 20, 13);
        upsertMastery(studentId, courseId, "Java多线程", 90, 15, 13);

        // 练习样例：3 次练习记录（供学生练习总览聚合查询）
        if (practiceMapper.selectCount(null) == 0) {
            insertPractice(studentId, courseId, "AI 智能练习", 50, 35);
            insertPractice(studentId, courseId, "集合专项练习", 30, 30);
            insertPractice(studentId, courseId, "多线程综合练习", 50, 45);
        }
    }

    private void upsertMastery(Long userId, Long courseId, String kp, double mastery, int attempts, int correct) {
        var existing = masteryMapper.selectOne(new LambdaQueryWrapper<com.example.learningassistant.progress.entity.KnowledgeMastery>()
                .eq(com.example.learningassistant.progress.entity.KnowledgeMastery::getUserId, userId)
                .eq(com.example.learningassistant.progress.entity.KnowledgeMastery::getCourseId, courseId)
                .eq(com.example.learningassistant.progress.entity.KnowledgeMastery::getKpName, kp));
        if (existing != null) {
            return;
        }
        var km = new com.example.learningassistant.progress.entity.KnowledgeMastery();
        km.setUserId(userId);
        km.setCourseId(courseId);
        km.setKpName(kp);
        km.setMastery(mastery);
        km.setAttempts(attempts);
        km.setCorrectCount(correct);
        masteryMapper.insert(km);
    }

    private void insertPractice(Long userId, Long courseId, String title, int total, int score) {
        var p = new com.example.learningassistant.practice.entity.Practice();
        p.setUserId(userId);
        p.setCourseId(courseId);
        p.setTitle(title);
        p.setTotalScore(total);
        p.setScore(score);
        p.setCreatedAt(LocalDateTime.now());
        practiceMapper.insert(p);
    }

    private void seedQuestions(Long courseId) {
        if (questionMapper.selectCount(null) > 0) {
            return;
        }
        insertQuestion(courseId, "单选", "HashMap 的默认初始容量是？",
                "[{\"k\":\"A\",\"v\":\"8\"},{\"k\":\"B\",\"v\":\"16\"},{\"k\":\"C\",\"v\":\"32\"},{\"k\":\"D\",\"v\":\"64\"}]",
                "B", "HashMap 默认初始容量为 16，负载因子 0.75。", "HashMap原理");
        insertQuestion(courseId, "多选", "下列属于 List 接口实现类的有？",
                "[{\"k\":\"A\",\"v\":\"ArrayList\"},{\"k\":\"B\",\"v\":\"LinkedList\"},{\"k\":\"C\",\"v\":\"HashSet\"},{\"k\":\"D\",\"v\":\"HashMap\"}]",
                "AB", "ArrayList 与 LinkedList 是 List 实现；HashSet 是 Set 实现，HashMap 是 Map 实现。", "Java集合框架");
        insertQuestion(courseId, "判断", "synchronized 修饰的实例方法锁住的是当前对象。",
                "[{\"k\":\"对\",\"v\":\"正确\"},{\"k\":\"错\",\"v\":\"错误\"}]",
                "对", "synchronized 实例方法以 this 作为内置锁。", "Java多线程");
        insertQuestion(courseId, "问答", "请简述面向对象三大特性的含义。",
                null,
                "封装：隐藏内部细节；继承：复用并扩展父类能力；多态：同一行为在不同对象上有不同表现。",
                "评分按要点覆盖度（封装/继承/多态各 3 分，示例 1 分）。", "面向对象");
    }

    private void insertQuestion(Long courseId, String type, String stem, String options,
                                String answer, String analysis, String kpName) {
        var q = new com.example.learningassistant.practice.entity.Question();
        q.setCourseId(courseId);
        q.setType(type);
        q.setStem(stem);
        q.setOptions(options);
        q.setAnswer(answer);
        q.setAnalysis(analysis);
        q.setKpName(kpName);
        q.setSource("SEED");
        q.setCreatedAt(LocalDateTime.now());
        questionMapper.insert(q);
    }

    private void seedKb(Long courseId) {
        var kb = kbService.kbList(courseId).stream()
                .filter(k -> "Java 入门资料".equals(k.getName()))
                .findFirst().orElseGet(() -> kbService.createKb(courseId, "Java 入门资料"));
        if (!kbService.documents(kb.getId()).isEmpty()) {
            return;
        }
        kbService.indexTextDocument(kb.getId(), "Java核心概念.md",
                "Java 是一门面向对象的编程语言，具有跨平台特性，通过 JVM 实现一次编译到处运行。\n"
                        + "Java 的内存分为堆、栈和方法区：对象实例存放在堆中，局部变量与引用存放在栈中，类元数据存放在方法区。\n"
                        + "HashMap 基于数组加链表（红黑树）实现，默认初始容量 16，负载因子 0.75，键值对按 key 的 hashCode 定位桶位，冲突时使用 equals 比较。\n"
                        + "Java 多线程编程常用手段：继承 Thread、实现 Runnable、使用 Executor 线程池与 synchronized/volatile 同步机制，注意死锁与线程安全问题。\n"
                        + "集合框架由 Collection（List/Set/Queue）与 Map 两大体系组成，常用实现包括 ArrayList、LinkedList、HashSet、TreeSet、HashMap、TreeMap。\n"
                        + "面向对象三大特性是封装、继承与多态，封装隐藏实现细节，继承实现代码复用，多态让同一接口呈现不同行为。");
    }
}
