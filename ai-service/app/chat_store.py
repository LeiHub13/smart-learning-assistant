"""答疑数据的持久化边界。

沿用 Java 旧版的两张 MySQL 表及原有 ID，不复制历史数据。轮次表记录生成和跨服务
结算状态，使消息保存与结算失败后的重试有明确依据。
"""
from contextlib import contextmanager
from datetime import date, datetime
import threading

from app import config


class ChatStoreError(Exception):
    pass


class SessionNotFound(ChatStoreError):
    pass


class TurnInProgress(ChatStoreError):
    pass


_schema_ready = False
_schema_lock = threading.Lock()


def _connect():
    import pymysql
    return pymysql.connect(
        host=config.CHAT_DB_HOST, port=config.CHAT_DB_PORT, user=config.CHAT_DB_USER,
        password=config.CHAT_DB_PASSWORD, database=config.CHAT_DB_NAME, charset="utf8mb4",
        autocommit=False, connect_timeout=10, read_timeout=30, write_timeout=30,
        cursorclass=pymysql.cursors.DictCursor,
        init_command="SET time_zone = '+08:00'",
    )


@contextmanager
def transaction():
    connection = _connect()
    try:
        with connection.cursor() as cursor:
            yield cursor
        connection.commit()
    except BaseException:
        connection.rollback()
        raise
    finally:
        connection.close()


def initialize():
    """创建/增量升级本服务持有的表，可重复执行，不改变已有会话和消息。"""
    global _schema_ready
    if _schema_ready:
        return
    with _schema_lock:
        if _schema_ready:
            return
        with transaction() as cur:
            cur.execute("""CREATE TABLE IF NOT EXISTS t_chat_session (
                id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT DEFAULT 1,
                user_id BIGINT, course_id BIGINT, kb_id BIGINT, kb_scope VARCHAR(10),
                socratic TINYINT DEFAULT 0, feynman TINYINT DEFAULT 0,
                title VARCHAR(100), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""")
            cur.execute("""CREATE TABLE IF NOT EXISTS t_chat_message (
                id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT DEFAULT 1,
                session_id BIGINT, role VARCHAR(20) NOT NULL, content TEXT NOT NULL,
                sources VARCHAR(1000), source_chunks VARCHAR(300), followups VARCHAR(600),
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""")
            for table, column, definition in (
                ("t_chat_session", "kb_scope", "VARCHAR(10) NULL"),
                ("t_chat_session", "socratic", "TINYINT DEFAULT 0"),
                ("t_chat_session", "feynman", "TINYINT DEFAULT 0"),
                ("t_chat_message", "source_chunks", "VARCHAR(300) NULL"),
                ("t_chat_message", "followups", "VARCHAR(600) NULL"),
            ):
                cur.execute("SELECT COUNT(*) AS n FROM information_schema.COLUMNS "
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME=%s AND COLUMN_NAME=%s",
                            (table, column))
                if not cur.fetchone()["n"]:
                    cur.execute(f"ALTER TABLE {table} ADD COLUMN {column} {definition}")
            cur.execute("""CREATE TABLE IF NOT EXISTS t_chat_turn (
                id VARCHAR(36) PRIMARY KEY,
                session_id BIGINT NOT NULL,
                user_id BIGINT NOT NULL,
                state VARCHAR(32) NOT NULL,
                active_session_id BIGINT NULL,
                reservation_id VARCHAR(36) NULL,
                user_message_id BIGINT NULL,
                assistant_message_id BIGINT NULL,
                memory_synced TINYINT NOT NULL DEFAULT 1,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                UNIQUE KEY uk_chat_turn_active_session (active_session_id),
                KEY idx_chat_turn_state_updated (state, updated_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""")
            for column, definition in (("user_message_id", "BIGINT NULL"),
                                       ("assistant_message_id", "BIGINT NULL"),
                                       ("memory_synced", "TINYINT NOT NULL DEFAULT 1")):
                cur.execute("SELECT COUNT(*) AS n FROM information_schema.COLUMNS "
                            "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_chat_turn' AND COLUMN_NAME=%s", (column,))
                if not cur.fetchone()["n"]:
                    cur.execute(f"ALTER TABLE t_chat_turn ADD COLUMN {column} {definition}")
            cur.execute("""CREATE TABLE IF NOT EXISTS t_chat_deleted_course (
                course_id BIGINT PRIMARY KEY,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""")
            cur.execute("""CREATE TABLE IF NOT EXISTS t_chat_memory_cleanup (
                session_id BIGINT PRIMARY KEY,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""")
        _schema_ready = True


def _time(value):
    return value.isoformat() if isinstance(value, (datetime, date)) else value


def _session(row):
    if row is None:
        return None
    return {"id": row["id"], "userId": row["user_id"], "courseId": row["course_id"],
            "kbId": row["kb_id"], "kbScope": row["kb_scope"],
            "socratic": bool(row["socratic"]), "feynman": bool(row["feynman"]),
            "title": row["title"], "createdAt": _time(row["created_at"])}


def _message(row):
    return {"id": row["id"], "sessionId": row["session_id"], "role": row["role"],
            "content": row["content"], "sources": row["sources"],
            "sourceChunks": row["source_chunks"], "followups": row["followups"],
            "createdAt": _time(row["created_at"])}


def _owned(cur, session_id, user_id, *, lock=False):
    cur.execute("SELECT * FROM t_chat_session WHERE id=%s AND user_id=%s" + (" FOR UPDATE" if lock else ""),
                (session_id, user_id))
    row = cur.fetchone()
    if not row:
        raise SessionNotFound("会话不存在或无权访问")
    return row


def sessions(user_id):
    with transaction() as cur:
        cur.execute("SELECT * FROM t_chat_session WHERE user_id=%s ORDER BY created_at DESC, id DESC", (user_id,))
        return [_session(row) for row in cur.fetchall()]


def session(session_id, user_id):
    with transaction() as cur:
        return _session(_owned(cur, session_id, user_id))


def create_session(user_id, course_id=None, kb_id=None, kb_scope=None):
    scope = ("course" if kb_scope == "course" else "single") if kb_id is not None else None
    with transaction() as cur:
        _check_course_not_deleted(cur, course_id)
        cur.execute("INSERT INTO t_chat_session (user_id,course_id,kb_id,kb_scope,title) VALUES (%s,%s,%s,%s,%s)",
                    (user_id, course_id, kb_id, scope, "新对话"))
        return _session(_owned(cur, cur.lastrowid, user_id))


def update_session(session_id, user_id, fields):
    with transaction() as cur:
        if fields.get("courseId") is not None:
            _check_course_not_deleted(cur, fields["courseId"])
        row = _owned(cur, session_id, user_id, lock=True)
        for key, column in (("title", "title"), ("courseId", "course_id"), ("kbId", "kb_id"),
                            ("socratic", "socratic"), ("feynman", "feynman")):
            value = fields.get(key)
            if value is not None and (key != "title" or str(value).strip()):
                row[column] = str(value).strip() if key == "title" else value
        if fields.get("kbScope") is not None and row["kb_id"] is not None:
            row["kb_scope"] = "course" if fields["kbScope"] == "course" else "single"
        if fields.get("feynman") is True:
            row["socratic"] = False
        cur.execute("UPDATE t_chat_session SET title=%s,course_id=%s,kb_id=%s,kb_scope=%s,socratic=%s,feynman=%s "
                    "WHERE id=%s AND user_id=%s",
                    (row["title"], row["course_id"], row["kb_id"], row["kb_scope"],
                     row["socratic"], row["feynman"], session_id, user_id))
        return _session(_owned(cur, session_id, user_id))


def messages(session_id, user_id):
    with transaction() as cur:
        _owned(cur, session_id, user_id)
        cur.execute("SELECT * FROM t_chat_message WHERE session_id=%s ORDER BY created_at ASC,id ASC", (session_id,))
        return [_message(row) for row in cur.fetchall()]


def _delete_locked(cur, row):
    session_id = row["id"]
    cur.execute("INSERT INTO t_chat_memory_cleanup (session_id) VALUES (%s) "
                "ON DUPLICATE KEY UPDATE updated_at=CURRENT_TIMESTAMP", (session_id,))
    # 保留轮次账本供异步退款或结算；正在执行的任务不能再写回已删除的会话。
    cur.execute("UPDATE t_chat_turn SET state='FAILED_RELEASE',active_session_id=NULL "
                "WHERE session_id=%s AND state IN ('STARTING','RUNNING')", (session_id,))
    cur.execute("UPDATE t_chat_turn SET memory_synced=1,active_session_id=NULL "
                "WHERE session_id=%s AND state IN ('SUCCESS_SETTLE','COMPLETE')", (session_id,))
    cur.execute("DELETE FROM t_chat_message WHERE session_id=%s", (session_id,))
    cur.execute("DELETE FROM t_chat_session WHERE id=%s", (session_id,))


def delete_session(session_id, user_id):
    with transaction() as cur:
        row = _owned(cur, session_id, user_id, lock=True)
        _delete_locked(cur, row)


def _check_course_not_deleted(cur, course_id):
    if course_id is None:
        return
    cur.execute("SELECT course_id FROM t_chat_deleted_course WHERE course_id=%s FOR UPDATE", (course_id,))
    if cur.fetchone():
        raise ChatStoreError("课程不存在")


def delete_course_sessions(course_id):
    with transaction() as cur:
        # 与会话创建串行化，防止删除课程后迟到的创建请求留下孤儿会话。
        cur.execute("INSERT INTO t_chat_deleted_course (course_id) VALUES (%s) "
                    "ON DUPLICATE KEY UPDATE course_id=course_id", (course_id,))
        cur.execute("SELECT * FROM t_chat_session WHERE course_id=%s FOR UPDATE", (course_id,))
        rows = list(cur.fetchall())
        for row in rows:
            _delete_locked(cur, row)
        return [row["id"] for row in rows]


def begin_turn(turn_id, session_id, user_id):
    import pymysql
    with transaction() as cur:
        _owned(cur, session_id, user_id, lock=True)
        try:
            cur.execute("INSERT INTO t_chat_turn (id,session_id,user_id,state,active_session_id) "
                        "VALUES (%s,%s,%s,'STARTING',%s)", (turn_id, session_id, user_id, session_id))
        except pymysql.err.IntegrityError as exc:
            if exc.args and exc.args[0] == 1062:
                raise TurnInProgress("该会话正在生成回答，请稍候") from exc
            raise


def start_turn(turn_id, session_id, user_id, question, reservation):
    with transaction() as cur:
        row = _owned(cur, session_id, user_id, lock=True)
        cur.execute("SELECT state FROM t_chat_turn WHERE id=%s FOR UPDATE", (turn_id,))
        turn = cur.fetchone()
        if not turn or turn["state"] != "STARTING":
            raise ChatStoreError("会话已删除或请求已取消")
        if row["title"] == "新对话":
            title = " ".join(question.split())
            if len(title) > 20:
                title = title[:20] + "…"
            cur.execute("UPDATE t_chat_session SET title=%s WHERE id=%s", (title, session_id))
        cur.execute("INSERT INTO t_chat_message (session_id,role,content) VALUES (%s,'user',%s)",
                    (session_id, question))
        message_id = cur.lastrowid
        cur.execute("UPDATE t_chat_turn SET state='RUNNING',reservation_id=%s,user_message_id=%s WHERE id=%s",
                    (reservation, message_id, turn_id))


def touch_turn(turn_id):
    with transaction() as cur:
        cur.execute("UPDATE t_chat_turn SET updated_at=CURRENT_TIMESTAMP WHERE id=%s AND state IN ('STARTING','RUNNING')",
                    (turn_id,))
        cur.execute("UPDATE t_chat_memory_cleanup SET updated_at=CURRENT_TIMESTAMP WHERE session_id="
                    "(SELECT session_id FROM t_chat_turn WHERE id=%s)", (turn_id,))


def finish_turn(turn_id, session_id, user_id, answer, meta):
    with transaction() as cur:
        _owned(cur, session_id, user_id, lock=True)
        cur.execute("SELECT * FROM t_chat_turn WHERE id=%s FOR UPDATE", (turn_id,))
        turn = cur.fetchone()
        if not turn or turn["state"] != "RUNNING":
            raise ChatStoreError("会话已删除或请求已取消")
        cur.execute("INSERT INTO t_chat_message (session_id,role,content,sources,source_chunks,followups) "
                    "VALUES (%s,'assistant',%s,%s,%s,%s)",
                    (session_id, answer, meta.get("sources") or None, meta.get("sourceChunks") or None,
                     meta.get("followups") or None))
        message_id = cur.lastrowid
        state = "SUCCESS_SETTLE" if turn["reservation_id"] else "COMPLETE"
        # 在本轮会话记忆完成同步前保留活动锁，避免下一轮读到旧上下文。
        cur.execute("UPDATE t_chat_turn SET state=%s,assistant_message_id=%s,memory_synced=0 WHERE id=%s",
                    (state, message_id, turn_id))
        return state


def fail_turn(turn_id):
    with transaction() as cur:
        cur.execute("SELECT state FROM t_chat_turn WHERE id=%s FOR UPDATE", (turn_id,))
        turn = cur.fetchone()
        if not turn:
            return None
        if turn["state"] in ("STARTING", "RUNNING"):
            cur.execute("UPDATE t_chat_turn SET state='FAILED_RELEASE',active_session_id=NULL WHERE id=%s", (turn_id,))
            return "FAILED_RELEASE"
        return turn["state"]


def finish_reconciliation(turn_id, expected_state):
    new_state = "COMPLETE" if expected_state == "SUCCESS_SETTLE" else "FAILED"
    with transaction() as cur:
        cur.execute("UPDATE t_chat_turn SET state=%s WHERE id=%s AND state=%s", (new_state, turn_id, expected_state))


def defer_reconciliation(turn_id, expected_state):
    with transaction() as cur:
        cur.execute("UPDATE t_chat_turn SET updated_at=CURRENT_TIMESTAMP WHERE id=%s AND state=%s",
                    (turn_id, expected_state))


def pending_reconciliation(stale_seconds=600):
    with transaction() as cur:
        # 运行线程每 30 秒续期；异常退出的轮次超时后进入退款队列。
        cur.execute("UPDATE t_chat_turn SET state='FAILED_RELEASE',active_session_id=NULL "
                    "WHERE state IN ('STARTING','RUNNING') "
                    "AND updated_at < (CURRENT_TIMESTAMP - INTERVAL %s SECOND)", (stale_seconds,))
        cur.execute("SELECT id,user_id,state,reservation_id FROM t_chat_turn "
                    "WHERE state IN ('SUCCESS_SETTLE','FAILED_RELEASE') ORDER BY updated_at ASC LIMIT 100")
        return list(cur.fetchall())


def pending_memory_cleanup(stale_seconds=600):
    with transaction() as cur:
        cur.execute("SELECT session_id,updated_at FROM t_chat_memory_cleanup "
                    "WHERE updated_at < (CURRENT_TIMESTAMP - INTERVAL %s SECOND) LIMIT 100", (stale_seconds,))
        return list(cur.fetchall())


def pending_memory_sync():
    with transaction() as cur:
        cur.execute("SELECT id FROM t_chat_turn WHERE memory_synced=0 "
                    "AND state IN ('SUCCESS_SETTLE','COMPLETE') ORDER BY updated_at ASC LIMIT 100")
        return [row["id"] for row in cur.fetchall()]


def sync_turn_memory(turn_id, write_memory):
    """串行化消息与记忆同步；先写入幂等记忆，再提交同步标记。"""
    with transaction() as cur:
        cur.execute("SELECT session_id,user_id FROM t_chat_turn WHERE id=%s", (turn_id,))
        identity = cur.fetchone()
        if not identity:
            return None
        session_id = identity["session_id"]
        cur.execute("SELECT id FROM t_chat_session WHERE id=%s FOR UPDATE", (session_id,))
        exists = cur.fetchone() is not None
        cur.execute("SELECT * FROM t_chat_turn WHERE id=%s FOR UPDATE", (turn_id,))
        turn = cur.fetchone()
        if not turn or turn["memory_synced"] or turn["state"] not in ("SUCCESS_SETTLE", "COMPLETE"):
            return None
        if not exists:
            cur.execute("UPDATE t_chat_turn SET memory_synced=1,active_session_id=NULL WHERE id=%s", (turn_id,))
            return None
        cur.execute("SELECT id,role,content FROM t_chat_message WHERE session_id=%s AND id IN (%s,%s)",
                    (session_id, turn["user_message_id"], turn["assistant_message_id"]))
        messages = {row["role"]: row["content"] for row in cur.fetchall()}
        if "user" not in messages or "assistant" not in messages:
            raise ChatStoreError("已保存的答疑消息不完整，无法同步记忆")
        write_memory(str(session_id), turn_id, messages["user"], messages["assistant"])
        cur.execute("UPDATE t_chat_turn SET memory_synced=1,active_session_id=NULL WHERE id=%s", (turn_id,))
        return {"sessionId": str(session_id), "userId": identity["user_id"],
                "question": messages["user"], "answer": messages["assistant"]}


def finish_memory_cleanup(session_id, updated_at=None):
    with transaction() as cur:
        if updated_at is None:
            cur.execute("DELETE FROM t_chat_memory_cleanup WHERE session_id=%s", (session_id,))
        else:
            # 清理期间若存活的生成线程续期，则保留任务供它结束后再次清理。
            cur.execute("DELETE FROM t_chat_memory_cleanup WHERE session_id=%s AND updated_at=%s",
                        (session_id, updated_at))


def past_questions(user_id, keyword="", exclude_session_id=None, limit=5):
    size = max(1, min(int(limit), 10))
    with transaction() as cur:
        sql = ("SELECT m.*,s.title AS session_title FROM t_chat_message m "
               "JOIN t_chat_session s ON s.id=m.session_id WHERE s.user_id=%s AND m.role='user'")
        params = [user_id]
        if exclude_session_id:
            sql += " AND s.id<>%s"
            params.append(exclude_session_id)
        if keyword.strip():
            sql += " AND m.content LIKE %s"
            params.append("%" + keyword.strip() + "%")
        sql += " ORDER BY m.id DESC LIMIT %s"
        params.append(size)
        cur.execute(sql, params)
        hits = list(cur.fetchall())
        result = []
        for hit in hits:
            cur.execute("SELECT content FROM t_chat_message WHERE session_id=%s AND role='assistant' "
                        "AND id>%s ORDER BY id ASC LIMIT 1", (hit["session_id"], hit["id"]))
            answer = cur.fetchone()
            result.append({"sessionId": hit["session_id"], "sessionTitle": hit["session_title"] or "",
                           "askedAt": _time(hit["created_at"]), "question": hit["content"][:200],
                           "answer": answer["content"][:400] if answer else None})
        return result
