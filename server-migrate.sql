-- =====================================================================
-- 服务器存量库迁移脚本（2026-09-09）
-- 用法（服务器上执行，注意 utf8mb4 参数）：
--   docker exec -i la-mysql mysql --default-character-set=utf8mb4 -uroot -p'密码' learning_assistant < server-migrate.sql
-- 特性：全部幂等（重复执行不报错、不插重）；schema.sql 仅供全新建库，本脚本用于老库补齐。
-- =====================================================================

-- ===== 1. t_user.email / t_user.avatar（幂等加列：不存在才 ALTER）=====
SET @ddl = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_user' AND COLUMN_NAME = 'email') = 0,
  'ALTER TABLE t_user ADD COLUMN email VARCHAR(100) NULL AFTER nickname',
  'SELECT ''t_user.email 已存在，跳过''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_user' AND COLUMN_NAME = 'avatar') = 0,
  'ALTER TABLE t_user ADD COLUMN avatar VARCHAR(500) NULL AFTER email',
  'SELECT ''t_user.avatar 已存在，跳过''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ===== 2. t_question.difficulty（幂等加列 + 存量题目回填难度）=====
SET @ddl = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_question' AND COLUMN_NAME = 'difficulty') = 0,
  'ALTER TABLE t_question ADD COLUMN difficulty VARCHAR(20) NULL AFTER kp_name',
  'SELECT ''t_question.difficulty 已存在，跳过''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

UPDATE t_question
SET difficulty = CASE type
      WHEN '单选' THEN '基础'
      WHEN '多选' THEN '基础'
      WHEN '判断' THEN '进阶'
      ELSE '综合' END
WHERE difficulty IS NULL;

-- ===== 3. t_study_log 学习时长表（新建，幂等）=====
CREATE TABLE IF NOT EXISTS t_study_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id   BIGINT DEFAULT 1,
    user_id     BIGINT NOT NULL,
    course_id   BIGINT NOT NULL DEFAULT 0,
    study_date  DATE NOT NULL,
    minutes     INT DEFAULT 0,
    UNIQUE KEY uk_study (user_id, course_id, study_date)
);

-- ===== 4. 课程数据补齐（幂等：同名跳过；owner_id=1 为 xiaoming，若 id 不同请替换）=====
INSERT INTO t_course (tenant_id, name, description, owner_id, owner_name, created_at)
SELECT 1, 'Java 编程基础', '涵盖 Java 核心语法、面向对象、集合框架、多线程等知识点的入门课程。', 1, '小明', NOW()
WHERE NOT EXISTS (SELECT 1 FROM t_course WHERE name = 'Java 编程基础');

INSERT INTO t_course (tenant_id, name, description, owner_id, owner_name, created_at)
SELECT 1, 'Python 快速入门', '从零掌握 Python 基础语法、函数、面向对象与常用标准库，适合编程新手。', 1, '小明', NOW()
WHERE NOT EXISTS (SELECT 1 FROM t_course WHERE name = 'Python 快速入门');

INSERT INTO t_course (tenant_id, name, description, owner_id, owner_name, created_at)
SELECT 1, '数据结构与算法', '线性表、树、图、排序与查找算法详解，配套经典题型训练。', 1, '小明', NOW()
WHERE NOT EXISTS (SELECT 1 FROM t_course WHERE name = '数据结构与算法');

INSERT INTO t_course (tenant_id, name, description, owner_id, owner_name, created_at)
SELECT 1, '数据库原理与应用', '以 MySQL 为例讲解关系模型、SQL 语言、索引优化与事务隔离级别。', 1, '小明', NOW()
WHERE NOT EXISTS (SELECT 1 FROM t_course WHERE name = '数据库原理与应用');

INSERT INTO t_course (tenant_id, name, description, owner_id, owner_name, created_at)
SELECT 1, '计算机网络基础', 'TCP/IP 协议栈逐层拆解：HTTP、TCP/UDP、IP 与局域网组网实践。', 1, '小明', NOW()
WHERE NOT EXISTS (SELECT 1 FROM t_course WHERE name = '计算机网络基础');

INSERT INTO t_course (tenant_id, name, description, owner_id, owner_name, created_at)
SELECT 1, 'Linux 操作系统实践', '常用命令、Shell 脚本、用户权限与软件部署，掌握服务器日常运维。', 1, '小明', NOW()
WHERE NOT EXISTS (SELECT 1 FROM t_course WHERE name = 'Linux 操作系统实践');

-- 小明补齐全量选课（幂等）
INSERT INTO t_course_user (tenant_id, course_id, user_id, joined_at)
SELECT 1, c.id, 1, NOW() FROM t_course c
WHERE c.owner_id = 1
  AND NOT EXISTS (SELECT 1 FROM t_course_user cu WHERE cu.course_id = c.id AND cu.user_id = 1);

-- ===== 5. 验证 =====
SELECT id, name FROM t_course;
SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND (COLUMN_NAME IN ('email', 'avatar', 'difficulty') OR TABLE_NAME = 't_study_log')
ORDER BY TABLE_NAME, COLUMN_NAME;

-- ===== （可选）课程乱码清理：若课程表仍有乱码行才执行，把已知完好课程的 id 列入 IN 保留 =====
-- DELETE FROM t_course_user WHERE course_id NOT IN (SELECT id FROM t_course);
-- DELETE FROM t_course WHERE name LIKE '??%' OR name = '' OR name IN ('Java', 'Python', 'Linux');
-- 执行后重新跑上面的第 4 节即可补回标准课程。
