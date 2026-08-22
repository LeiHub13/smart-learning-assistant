-- V2 骨架建表脚本（H2 MODE=MySQL 兼容；MySQL 8 可直接使用）
-- 仅包含骨架可运行所需的最小表集，完整表设计见 docs/02-系统架构设计.md §6

CREATE TABLE IF NOT EXISTS t_user (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id   BIGINT DEFAULT 1,
    username    VARCHAR(50)  NOT NULL UNIQUE,
    password    VARCHAR(100) NOT NULL,
    nickname    VARCHAR(50),
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_course (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id    BIGINT DEFAULT 1,
    name         VARCHAR(100) NOT NULL,
    description  VARCHAR(500),
    owner_id     BIGINT,
    owner_name   VARCHAR(50),
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_knowledge_mastery (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id     BIGINT DEFAULT 1,
    user_id       BIGINT,
    course_id     BIGINT,
    kp_name       VARCHAR(50) NOT NULL,
    mastery       DOUBLE DEFAULT 0,
    attempts      INT DEFAULT 0,
    correct_count INT DEFAULT 0,
    UNIQUE (user_id, course_id, kp_name)
);

CREATE TABLE IF NOT EXISTS t_practice (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id   BIGINT DEFAULT 1,
    user_id     BIGINT,
    course_id   BIGINT,
    title       VARCHAR(100),
    total_score INT DEFAULT 0,
    score       INT DEFAULT 0,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_llm_call_log (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id         BIGINT DEFAULT 1,
    user_id           BIGINT,
    scene             VARCHAR(30),
    model             VARCHAR(50),
    prompt_tokens     INT DEFAULT 0,
    completion_tokens INT DEFAULT 0,
    latency_ms        INT DEFAULT 0,
    cost              DECIMAL(10, 4) DEFAULT 0,
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_course_user (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id  BIGINT DEFAULT 1,
    course_id  BIGINT,
    user_id    BIGINT,
    joined_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_document (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    kb_id        BIGINT,
    file_name    VARCHAR(200),
    file_url     VARCHAR(500),
    file_type    VARCHAR(100),
    file_size    BIGINT DEFAULT 0,
    chunk_count  INT DEFAULT 0,
    parse_status VARCHAR(20),
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_knowledge_base (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id  BIGINT DEFAULT 1,
    course_id  BIGINT,
    name       VARCHAR(100) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_chunk (
    id      BIGINT AUTO_INCREMENT PRIMARY KEY,
    doc_id  BIGINT,
    kb_id   BIGINT,
    content TEXT NOT NULL,
    idx     INT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS t_question (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id  BIGINT DEFAULT 1,
    course_id  BIGINT,
    type       VARCHAR(10) NOT NULL,
    stem       TEXT NOT NULL,
    options    TEXT,
    answer     TEXT,
    analysis   TEXT,
    kp_name    VARCHAR(50),
    difficulty VARCHAR(20),
    source     VARCHAR(10),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_chat_session (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id  BIGINT DEFAULT 1,
    user_id    BIGINT,
    course_id  BIGINT,
    kb_id      BIGINT,
    title      VARCHAR(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_chat_message (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id  BIGINT DEFAULT 1,
    session_id BIGINT,
    role       VARCHAR(20) NOT NULL,
    content    TEXT NOT NULL,
    sources    VARCHAR(1000),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_generated_content (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id  BIGINT DEFAULT 1,
    user_id    BIGINT,
    course_id  BIGINT,
    type       VARCHAR(20) NOT NULL,
    title      VARCHAR(100),
    content    TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_practice_question (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id   BIGINT DEFAULT 1,
    practice_id BIGINT,
    question_id BIGINT,
    user_answer TEXT,
    score       INT DEFAULT 0,
    correct     BOOLEAN DEFAULT FALSE,
    review      TEXT,
    kp_name     VARCHAR(50)
);

-- ========== 新增：学习计划 / 打卡 / 通知 / 学习报告 ==========

CREATE TABLE IF NOT EXISTS t_study_plan (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id   BIGINT DEFAULT 1,
    user_id     BIGINT,
    course_id   BIGINT,
    goal        VARCHAR(500),
    days        INT DEFAULT 7,
    status      VARCHAR(20) DEFAULT 'active',
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_plan_task (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id   BIGINT DEFAULT 1,
    plan_id     BIGINT,
    day_no      INT,
    task_date   DATE,
    title       VARCHAR(200),
    tasks       TEXT,
    focus_kp    VARCHAR(100),
    done        BOOLEAN DEFAULT FALSE,
    done_at     TIMESTAMP NULL
);

CREATE TABLE IF NOT EXISTS t_notification (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id   BIGINT DEFAULT 1,
    user_id     BIGINT,
    type        VARCHAR(20) DEFAULT 'review',
    title       VARCHAR(200),
    content     TEXT,
    read_flag   BOOLEAN DEFAULT FALSE,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_report (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id   BIGINT DEFAULT 1,
    user_id     BIGINT,
    course_id   BIGINT,
    period      VARCHAR(20),
    title       VARCHAR(200),
    content     TEXT,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 掌握度衰减：记录最近练习时间，供间隔重复复习提醒计算
ALTER TABLE t_knowledge_mastery ADD COLUMN IF NOT EXISTS last_practice_at TIMESTAMP NULL;
