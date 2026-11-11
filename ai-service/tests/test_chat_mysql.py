"""会话服务真实 MySQL 集成测试；仅在显式指定独立 chat_test 库时运行。"""
import json
import os
import threading
import uuid

import pytest
from fastapi.testclient import TestClient

from app import chat_api, chat_java, chat_store, config, main, memory

pytestmark = pytest.mark.skipif(os.getenv("CHAT_TEST_MYSQL") != "1", reason="需要独立的 MySQL 测试库")


@pytest.fixture(scope="module", autouse=True)
def isolated_mysql(tmp_path_factory):
    assert config.CHAT_DB_NAME == "chat_test", "测试只允许访问独立的 chat_test 数据库"
    assert config.CHAT_DB_HOST in ("127.0.0.1", "localhost"), "测试只允许访问本机数据库"
    original_memory_dir = config.MEMORY_DIR
    config.MEMORY_DIR = str(tmp_path_factory.mktemp("chat-memory"))
    chat_store.initialize()
    yield
    config.MEMORY_DIR = original_memory_dir


@pytest.fixture
def user_id():
    return int(uuid.uuid4().int % 1_000_000_000) + 1_000_000_000


@pytest.fixture
def services(monkeypatch, user_id):
    billing = []
    monkeypatch.setattr(chat_java, "authenticate", lambda token: user_id if token == "Bearer good" else
                        (_ for _ in ()).throw(chat_java.JavaBridgeError("未登录或登录已过期", 401)))
    monkeypatch.setattr(chat_java, "context", lambda uid, course_id, kb_id, kb_scope:
                        {"courseId": course_id, "kbId": kb_id, "kbIds": [kb_id] if kb_id else [],
                         "kbName": "测试知识库", "note": ""})
    monkeypatch.setattr(chat_java, "reserve", lambda uid, turn: billing.append(("reserve", uid, turn)) or turn)
    monkeypatch.setattr(chat_java, "settle", lambda turn: billing.append(("settle", turn)))
    monkeypatch.setattr(chat_java, "release", lambda uid, turn: billing.append(("release", uid, turn)))
    monkeypatch.setattr(chat_java, "pending_actions", lambda uid, sid: [])
    return billing


def test_legacy_rows_are_reused_and_user_isolation_is_enforced(user_id):
    with chat_store.transaction() as cur:
        cur.execute("INSERT INTO t_chat_session(user_id,title) VALUES (%s,%s)", (user_id, "旧会话"))
        session_id = cur.lastrowid
        cur.execute("INSERT INTO t_chat_message(session_id,role,content,sources) VALUES (%s,'assistant',%s,%s)",
                    (session_id, "旧回答", "1"))
        message_id = cur.lastrowid
    assert chat_store.session(session_id, user_id)["title"] == "旧会话"
    assert chat_store.messages(session_id, user_id)[0]["id"] == message_id
    with pytest.raises(chat_store.SessionNotFound):
        chat_store.messages(session_id, user_id + 1)
    with pytest.raises(chat_store.SessionNotFound):
        chat_store.update_session(session_id, user_id + 1, {"title": "越权修改"})
    with pytest.raises(chat_store.SessionNotFound):
        chat_store.delete_session(session_id, user_id + 1)
    assert chat_store.session(session_id, user_id)["title"] == "旧会话"


def test_http_stream_saves_answer_references_and_finishes_billing(monkeypatch, services, user_id):
    seen = []

    def fake_stream(scene, question, **kwargs):
        seen.append((scene, question, kwargs["user_id"], kwargs["kb_id"]))
        yield "第一段"
        yield "第二段"
        kwargs["meta"].update({"sources": "1,2", "sourceChunks": "10,11", "followups": ["接下来？"]})

    action_calls = []
    monkeypatch.setattr(chat_java, "pending_actions", lambda uid, sid:
                        action_calls.append(sid) or ([{"id": 3}] if len(action_calls) == 1 else [{"id": 3}, {"id": 4}]))
    monkeypatch.setattr(chat_api.chains, "stream", fake_stream)
    client = TestClient(main.app)
    headers = {"Authorization": "Bearer good"}
    created = client.post("/api/chat/sessions", headers=headers,
                          json={"courseId": 7, "kbId": 8, "kbScope": "course"}).json()["data"]
    response = client.post(f"/api/chat/sessions/{created['id']}/stream", headers=headers, json={"message": "解释一下"})
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")
    events = [json.loads(line[6:]) for line in response.text.splitlines() if line.startswith("data: ")]
    assert [event["delta"] for event in events if "delta" in event] == ["第一段", "第二段"]
    assert events[-1]["sources"] == "1,2"
    assert events[-1]["sourceChunks"] == "10,11"
    assert events[-1]["actions"] == [{"id": 4}]
    assert "\n\n" in response.text  # 与 Web 客户端的 SSE 分帧兼容
    rows = chat_store.messages(created["id"], user_id)
    assert [row["role"] for row in rows] == ["user", "assistant"]
    assert rows[-1]["content"] == "第一段第二段"
    assert rows[-1]["sources"] == "1,2"
    assert rows[-1]["sourceChunks"] == "10,11"
    assert json.loads(rows[-1]["followups"]) == ["接下来？"]
    assert seen == [("agent", "解释一下", user_id, 8)]
    assert [call[0] for call in services] == ["reserve", "settle"]
    assert client.get("/api/chat/sessions", headers={"Authorization": "Bearer bad"}).status_code == 401
    assert client.get(f"/ai/internal/chat/sessions/{created['id']}/messages?userId={user_id}").status_code == 403


def test_generation_failure_releases_reservation_without_saving_answer(monkeypatch, services, user_id):
    def failed_stream(*args, **kwargs):
        yield "部分回答"
        raise RuntimeError("模型失败")

    monkeypatch.setattr(chat_api.chains, "stream", failed_stream)
    client = TestClient(main.app)
    headers = {"Authorization": "Bearer good"}
    session_id = client.post("/api/chat/sessions", headers=headers, json={}).json()["data"]["id"]
    response = client.post(f"/api/chat/sessions/{session_id}/stream", headers=headers, json={"message": "问题"})
    assert "生成失败" in response.text
    assert [m["role"] for m in chat_store.messages(session_id, user_id)] == ["user"]
    assert [call[0] for call in services] == ["reserve", "release"]


def test_disconnect_does_not_cancel_background_save(monkeypatch, services, user_id):
    session = chat_store.create_session(user_id)
    turn = str(uuid.uuid4())
    chat_store.begin_turn(turn, session["id"], user_id)
    chat_store.start_turn(turn, session["id"], user_id, "问题", turn)
    monkeypatch.setattr(chat_api.chains, "stream", lambda *args, **kwargs: iter(["完成的回答"]))
    subscriber = chat_api._Subscriber()
    subscriber.close()
    chat_api._generate(turn, user_id, session, {"kbId": None}, "问题", set(), subscriber)
    assert chat_store.messages(session["id"], user_id)[-1]["content"] == "完成的回答"
    assert services == [("settle", turn)]
    assert subscriber.items.empty()


def test_deleting_session_during_generation_clears_memory_and_releases(monkeypatch, tmp_path, services, user_id):
    monkeypatch.setattr(config, "MEMORY_DIR", str(tmp_path))
    entered, proceed = threading.Event(), threading.Event()

    def slow_stream(*args, **kwargs):
        entered.set()
        assert proceed.wait(10)
        memory.add(kwargs["session_id"], "assistant", "删除后产生的临时记忆")
        yield "回答"

    monkeypatch.setattr(chat_api.chains, "stream", slow_stream)
    session = chat_store.create_session(user_id)
    turn = str(uuid.uuid4())
    chat_store.begin_turn(turn, session["id"], user_id)
    chat_store.start_turn(turn, session["id"], user_id, "问题", turn)
    worker = threading.Thread(target=chat_api._generate,
                              args=(turn, user_id, session, {"kbId": None}, "问题", set(), chat_api._Subscriber()))
    worker.start()
    assert entered.wait(10)
    chat_store.delete_session(session["id"], user_id)
    proceed.set()
    worker.join(10)
    assert not worker.is_alive()
    assert not os.path.exists(memory._file(str(session["id"])))
    assert ("release", user_id, turn) in services
    with pytest.raises(chat_store.SessionNotFound):
        chat_store.session(session["id"], user_id)


def test_settlement_failure_is_retried_without_refunding_saved_answer(monkeypatch, services, user_id):
    attempts = []
    session = chat_store.create_session(user_id)
    turn = str(uuid.uuid4())
    chat_store.begin_turn(turn, session["id"], user_id)
    chat_store.start_turn(turn, session["id"], user_id, "问题", turn)
    monkeypatch.setattr(chat_api.chains, "stream", lambda *args, **kwargs: iter(["已保存"] ))

    def settle(reservation):
        attempts.append(reservation)
        if len(attempts) == 1:
            raise chat_java.JavaBridgeError("暂时不可用")

    monkeypatch.setattr(chat_java, "settle", settle)
    chat_api._generate(turn, user_id, session, {"kbId": None}, "问题", set(), chat_api._Subscriber())
    assert chat_store.messages(session["id"], user_id)[-1]["content"] == "已保存"
    with chat_store.transaction() as cur:
        cur.execute("SELECT state FROM t_chat_turn WHERE id=%s", (turn,))
        assert cur.fetchone()["state"] == "SUCCESS_SETTLE"
    chat_api.reconcile_pending()
    with chat_store.transaction() as cur:
        cur.execute("SELECT state FROM t_chat_turn WHERE id=%s", (turn,))
        assert cur.fetchone()["state"] == "COMPLETE"
    assert [reservation for reservation in attempts if reservation == turn] == [turn, turn]
    assert not any(call[0] == "release" and call[2] == turn for call in services)


def test_memory_sync_retries_without_duplicate_turn(monkeypatch, user_id):
    session = chat_store.create_session(user_id)
    turn = str(uuid.uuid4())
    chat_store.begin_turn(turn, session["id"], user_id)
    chat_store.start_turn(turn, session["id"], user_id, "问题", turn)
    chat_store.finish_turn(turn, session["id"], user_id, "答案", {})

    def interrupted_after_write(session_id, turn_id, question, answer):
        memory.add_turn_once(session_id, turn_id, question, answer)
        raise OSError("模拟写入后进程中断")

    with pytest.raises(OSError):
        chat_store.sync_turn_memory(turn, interrupted_after_write)
    with pytest.raises(chat_store.TurnInProgress):
        chat_store.begin_turn(str(uuid.uuid4()), session["id"], user_id)
    monkeypatch.setattr(chat_api.chains, "_after_remember", lambda *args: None)
    chat_api._sync_turn_memory(turn)
    assert [row["content"] for row in memory.recent(str(session["id"]))] == ["问题", "答案"]
    chat_store.begin_turn(str(uuid.uuid4()), session["id"], user_id)


def test_unsaved_answer_is_not_added_to_memory(monkeypatch, services, user_id):
    session = chat_store.create_session(user_id)
    turn = str(uuid.uuid4())
    chat_store.begin_turn(turn, session["id"], user_id)
    chat_store.start_turn(turn, session["id"], user_id, "问题", turn)
    monkeypatch.setattr(chat_api.chains, "stream", lambda *args, **kwargs: iter(["回答"]))
    monkeypatch.setattr(chat_store, "finish_turn", lambda *args, **kwargs:
                        (_ for _ in ()).throw(OSError("模拟消息保存失败")))
    chat_api._generate(turn, user_id, session, {"kbId": None}, "问题", set(), chat_api._Subscriber())
    assert memory.recent(str(session["id"])) == []
    assert [call[0] for call in services] == ["release"]


def test_course_cleanup_prevents_late_session_creation(user_id):
    course_id = user_id + 3_000_000_000
    session = chat_store.create_session(user_id, course_id=course_id)
    assert session["id"] in chat_store.delete_course_sessions(course_id)
    with pytest.raises(chat_store.ChatStoreError):
        chat_store.create_session(user_id, course_id=course_id)


def test_only_one_active_turn_per_session(user_id):
    session = chat_store.create_session(user_id)
    first = str(uuid.uuid4())
    chat_store.begin_turn(first, session["id"], user_id)
    with pytest.raises(chat_store.TurnInProgress):
        chat_store.begin_turn(str(uuid.uuid4()), session["id"], user_id)
    assert chat_store.fail_turn(first) == "FAILED_RELEASE"
    chat_store.begin_turn(str(uuid.uuid4()), session["id"], user_id)
