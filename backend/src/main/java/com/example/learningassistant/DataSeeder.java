package com.example.learningassistant;

import com.example.learningassistant.course.entity.Course;
import com.example.learningassistant.course.mapper.CourseMapper;
import com.example.learningassistant.kb.service.KbService;
import com.example.learningassistant.practice.entity.Practice;
import com.example.learningassistant.practice.entity.Question;
import com.example.learningassistant.practice.mapper.PracticeMapper;
import com.example.learningassistant.practice.mapper.QuestionMapper;
import com.example.learningassistant.progress.entity.KnowledgeMastery;
import com.example.learningassistant.progress.mapper.KnowledgeMasteryMapper;
import com.example.learningassistant.user.entity.User;
import com.example.learningassistant.user.mapper.UserMapper;
import com.example.learningassistant.user.service.AuthService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 演示数据初始化：账号、课程、掌握度与练习样例（供 AnalyticsMapper 聚合查询演示）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final UserMapper userMapper;
    private final CourseMapper courseMapper;
    private final KnowledgeMasteryMapper masteryMapper;
    private final PracticeMapper practiceMapper;
    private final QuestionMapper questionMapper;
    private final KbService kbService;

    @Override
    public void run(String... args) {
        Long xiaomingId = seedUser("xiaoming", "小明");
        seedUser("xiaohong", "小红");
        seedUser("xiaoyu", "小宇");
        List<Course> courses = seedCourses(xiaomingId);
        Long courseId = courses.get(0).getId();
        seedMasteryAndPractices(xiaomingId, courseId);
        seedQuestions(courseId);
        seedKbs(courses);
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
        u.setNickname(nickname);
        u.setCreatedAt(LocalDateTime.now());
        userMapper.insert(u);
        return u.getId();
    }

    /**
     * 种子课程：表为空时批量插入演示课程；返回全部课程（演示数据挂在第一门课下）。
     */
    private List<Course> seedCourses(Long ownerId) {
        Long existing = courseMapper.selectCount(null);
        if (existing != null && existing > 0) {
            return courseMapper.selectList(null);
        }
        String[][] courses = {
                {"Java 编程基础", "涵盖 Java 核心语法、面向对象、集合框架、多线程等知识点的入门课程。"},
                {"Python 快速入门", "从零掌握 Python 基础语法、函数、面向对象与常用标准库，适合编程新手。"},
                {"数据结构与算法", "线性表、树、图、排序与查找算法详解，配套经典题型训练。"},
                {"数据库原理与应用", "以 MySQL 为例讲解关系模型、SQL 语言、索引优化与事务隔离级别。"},
                {"计算机网络基础", "TCP/IP 协议栈逐层拆解：HTTP、TCP/UDP、IP 与局域网组网实践。"},
                {"Linux 操作系统实践", "常用命令、Shell 脚本、用户权限与软件部署，掌握服务器日常运维。"}
        };
        for (String[] c : courses) {
            Course course = new Course();
            course.setName(c[0]);
            course.setDescription(c[1]);
            course.setOwnerId(ownerId);
            course.setOwnerName("小明");
            course.setCreatedAt(LocalDateTime.now());
            courseMapper.insert(course);
        }
        return courseMapper.selectList(null);
    }

    private void seedMasteryAndPractices(Long userId, Long courseId) {
        // 掌握度样例：3 个知识点，掌握度依次为 40 / 65 / 90
        upsertMastery(userId, courseId, "HashMap原理", 40, 10, 4);
        upsertMastery(userId, courseId, "Java集合框架", 65, 20, 13);
        upsertMastery(userId, courseId, "Java多线程", 90, 15, 13);

        // 练习样例：3 次练习记录（供练习总览聚合查询）
        if (practiceMapper.selectCount(null) == 0) {
            insertPractice(userId, courseId, "AI 智能练习", 50, 35);
            insertPractice(userId, courseId, "集合专项练习", 30, 30);
            insertPractice(userId, courseId, "多线程综合练习", 50, 45);
        }
    }

    private void upsertMastery(Long userId, Long courseId, String kp, double mastery, int attempts, int correct) {
        var existing = masteryMapper.selectOne(new LambdaQueryWrapper<KnowledgeMastery>()
                .eq(KnowledgeMastery::getUserId, userId)
                .eq(KnowledgeMastery::getCourseId, courseId)
                .eq(KnowledgeMastery::getKpName, kp));
        if (existing != null) {
            return;
        }
        var km = new KnowledgeMastery();
        km.setUserId(userId);
        km.setCourseId(courseId);
        km.setKpName(kp);
        km.setMastery(mastery);
        km.setAttempts(attempts);
        km.setCorrectCount(correct);
        masteryMapper.insert(km);
    }

    private void insertPractice(Long userId, Long courseId, String title, int total, int score) {
        var p = new Practice();
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
        var q = new Question();
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

    /**
     * 每门种子课程配套一个演示知识库（名称幂等、文档为空才索引）。
     */
    private void seedKbs(List<Course> courses) {
        Map<String, Course> byName = courses.stream()
                .collect(Collectors.toMap(Course::getName, c -> c, (a, b) -> a));
        seedKb(byName.get("Java 编程基础"), "Java 入门资料", "Java核心概念.md", DOC_JAVA);
        seedKb(byName.get("Python 快速入门"), "Python 入门资料", "Python核心概念.md", DOC_PYTHON);
        seedKb(byName.get("数据结构与算法"), "数据结构资料", "数据结构与算法要点.md", DOC_DS);
        seedKb(byName.get("数据库原理与应用"), "数据库资料", "数据库核心知识.md", DOC_DB);
        seedKb(byName.get("计算机网络基础"), "网络资料", "计算机网络要点.md", DOC_NET);
        seedKb(byName.get("Linux 操作系统实践"), "Linux 资料", "Linux常用操作.md", DOC_LINUX);
    }

    private void seedKb(Course course, String kbName, String docName, String content) {
        if (course == null) {
            return;
        }
        var kb = kbService.kbList(course.getId()).stream()
                .filter(k -> kbName.equals(k.getName()))
                .findFirst().orElseGet(() -> kbService.createKb(course.getId(), kbName));
        if (!kbService.documents(kb.getId()).isEmpty()) {
            return;
        }
        try {
            kbService.indexTextDocument(kb.getId(), docName, content);
        } catch (Exception e) {
            // Embedding 服务不可用时不应阻断启动，下次重启自动重试
            log.warn("演示知识库 {} 索引失败，将在下次启动重试: {}", kbName, e.getMessage());
        }
    }

    private static final String DOC_JAVA = "Java 是一门面向对象的编程语言，具有跨平台特性，通过 JVM 实现一次编译到处运行。\n"
            + "Java 的内存分为堆、栈和方法区：对象实例存放在堆中，局部变量与引用存放在栈中，类元数据存放在方法区。\n"
            + "HashMap 基于数组加链表（红黑树）实现，默认初始容量 16，负载因子 0.75，键值对按 key 的 hashCode 定位桶位，冲突时使用 equals 比较。\n"
            + "Java 多线程编程常用手段：继承 Thread、实现 Runnable、使用 Executor 线程池与 synchronized/volatile 同步机制，注意死锁与线程安全问题。\n"
            + "集合框架由 Collection（List/Set/Queue）与 Map 两大体系组成，常用实现包括 ArrayList、LinkedList、HashSet、TreeSet、HashMap、TreeMap。\n"
            + "面向对象三大特性是封装、继承与多态，封装隐藏实现细节，继承实现代码复用，多态让同一接口呈现不同行为。";

    private static final String DOC_PYTHON = "Python 是一门解释型高级编程语言，语法简洁，广泛用于数据分析、人工智能与自动化脚本。\n"
            + "基本数据类型包括整数、浮点数、字符串、列表、元组、字典与集合，变量无需声明类型，动态绑定。\n"
            + "函数使用 def 定义，支持默认参数、可变参数与关键字参数；模块通过 import 引入，包是模块的集合。\n"
            + "面向对象方面通过 class 定义类，支持继承与多态，__init__ 与 __str__ 等特殊方法由解释器自动调用。\n"
            + "列表推导式与生成器是特色语法，可以简洁高效地构造序列；装饰器用于在不修改函数的前提下增强其行为。\n"
            + "常用标准库：os（系统交互）、sys（解释器环境）、json（数据序列化）、datetime（日期时间），生态库 requests 与 numpy 用途广泛。";

    private static final String DOC_DS = "数据结构是组织与存储数据的方式，算法是解决问题的步骤描述，两者共同决定程序性能。\n"
            + "线性结构包括数组、链表、栈与队列：数组支持随机访问，链表擅长插入删除，栈后进先出，队列先进先出。\n"
            + "树形结构中，二叉搜索树支持对数级查找，AVL 与红黑树通过旋转维持平衡，堆常用于实现优先队列。\n"
            + "图用邻接矩阵或邻接表存储，遍历有深度优先（DFS）与广度优先（BFS），最短路径常用 Dijkstra 与 Floyd 算法。\n"
            + "排序算法：快速排序平均 O(nlogn) 但不稳定，归并排序稳定且性能平稳，堆排序空间占用小，插入排序适合小规模或近有序数据。\n"
            + "哈希表通过散列函数实现平均 O(1) 查找，冲突解决常用链地址法与开放定址法。";

    private static final String DOC_DB = "数据库系统由数据库、数据库管理系统与应用程序组成，关系型数据库以二维表组织数据。\n"
            + "SQL 分为三类：DDL 负责表结构定义，DML 负责数据增删改查，DCL 负责权限控制。\n"
            + "索引是提高查询速度的关键结构，MySQL InnoDB 采用 B+ 树索引：主键索引叶子节点存放整行数据，二级索引需要回表查询。\n"
            + "事务具有 ACID 四大特性：原子性、一致性、隔离性与持久性，InnoDB 通过 redo log 与 undo log 保障。\n"
            + "事务隔离级别从低到高为读未提交、读已提交、可重复读与串行化，InnoDB 默认可重复读，并通过 MVCC 与间隙锁防止幻读。\n"
            + "慢查询优化常见手段：建立合适的联合索引、避免索引失效写法、深分页优化与读写分离。";

    private static final String DOC_NET = "计算机网络按功能分为物理层、数据链路层、网络层、传输层与应用层，TCP/IP 模型将其简化为四层。\n"
            + "IP 协议负责网络层寻址与路由：IPv4 地址 32 位，子网掩码划分网络号与主机号，NAT 技术缓解地址枯竭。\n"
            + "传输层 TCP 提供可靠连接：三次握手建立连接，四次挥手释放连接，通过序号确认、超时重传与滑动窗口实现可靠传输和流量控制。\n"
            + "UDP 无连接、开销小、时延低，适合音视频直播与实时通信场景。\n"
            + "应用层 HTTP 基于 TCP，常用方法有 GET、POST、PUT、DELETE；状态码 200、301、404、500 分别表示成功、重定向、资源不存在与服务端错误。\n"
            + "HTTPS 在 HTTP 与 TCP 之间加入 TLS 层：非对称算法交换密钥，对称算法加密数据，兼顾安全与性能。";

    private static final String DOC_LINUX = "Linux 是开源的类 Unix 操作系统，广泛用于服务器、云计算与嵌入式领域，一切皆文件是其核心设计。\n"
            + "常用命令：ls 查看目录，cd 切换目录，cp/mv/rm 复制移动删除，grep 文本搜索，find 文件查找，ps/top 查看进程。\n"
            + "权限体系分为所有者、所属组与其他人三类，每类有读、写、执行三种权限，chmod 修改权限，chown 修改归属。\n"
            + "Shell 脚本以 #!/bin/bash 开头，支持变量、条件判断、循环与函数，管道与重定向可以灵活组合命令。\n"
            + "软件管理：CentOS 使用 yum，Ubuntu 使用 apt；服务通过 systemctl 控制启停与开机自启。\n"
            + "日志排查常看 /var/log 目录，tail -f 实时追踪，journalctl 查看系统日志，netstat 与 curl 是网络调试利器。";
}
