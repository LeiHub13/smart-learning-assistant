"""旧版答疑表升级测试，只在显式启用的本机隔离 MySQL 13306 实例运行。"""
import os
import uuid

import pytest

from app import chat_store, config

pytestmark = pytest.mark.skipif(os.getenv("CHAT_TEST_UPGRADE") != "1", reason="需要本机隔离的 MySQL 升级测试实例")


def test_legacy_chat_tables_upgrade_without_losing_ids_or_messages():
    import pymysql

    assert config.CHAT_DB_HOST == "127.0.0.1" and config.CHAT_DB_PORT == 13306
    database = "codex_chat_upgrade_" + uuid.uuid4().hex[:12]
    previous = (config.CHAT_DB_NAME, config.CHAT_DB_USER, config.CHAT_DB_PASSWORD, chat_store._schema_ready)
    admin = pymysql.connect(host="127.0.0.1", port=13306, user="root", password="", autocommit=True)
    try:
        with admin.cursor() as cur:
            cur.execute(f"CREATE DATABASE `{database}` CHARACTER SET utf8mb4")
        config.CHAT_DB_NAME, config.CHAT_DB_USER, config.CHAT_DB_PASSWORD = database, "root", ""
        chat_store._schema_ready = False
        with chat_store.transaction() as cur:
            cur.execute("""CREATE TABLE t_chat_session (
                id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT DEFAULT 1, user_id BIGINT,
                course_id BIGINT, kb_id BIGINT, title VARCHAR(100), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""")
            cur.execute("""CREATE TABLE t_chat_message (
                id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT DEFAULT 1, session_id BIGINT,
                role VARCHAR(20) NOT NULL, content TEXT NOT NULL, sources VARCHAR(1000),
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""")
            cur.execute("INSERT INTO t_chat_session (id,user_id,title) VALUES (77,123,'旧会话')")
            cur.execute("INSERT INTO t_chat_message (id,session_id,role,content,sources) "
                        "VALUES (88,77,'assistant','旧答案','1')")
        chat_store.initialize()
        session = chat_store.session(77, 123)
        messages = chat_store.messages(77, 123)
        assert session["id"] == 77 and session["title"] == "旧会话"
        assert session["socratic"] is False and session["feynman"] is False
        assert messages[0]["id"] == 88 and messages[0]["content"] == "旧答案"
        assert messages[0]["sources"] == "1" and messages[0]["sourceChunks"] is None
    finally:
        config.CHAT_DB_NAME, config.CHAT_DB_USER, config.CHAT_DB_PASSWORD, chat_store._schema_ready = previous
        # 数据库名由本测试生成，且只能连接本机的隔离测试实例。
        with admin.cursor() as cur:
            cur.execute(f"DROP DATABASE IF EXISTS `{database}`")
        admin.close()
