"""仅显式启用时，验证隔离的真实 Java、Python 与 MySQL 之间的答疑链路。"""
import hashlib
import os
import time
import uuid

import httpx
import pytest

from app import chat_store, config

pytestmark = pytest.mark.skipif(os.getenv("CHAT_TEST_LIVE") != "1", reason="需要隔离的 Java/Python/MySQL 测试服务")


def test_real_services_auth_chat_billing_flashcards_and_course_cleanup(monkeypatch):
    assert config.CHAT_DB_HOST in ("127.0.0.1", "localhost")
    # 与其他使用替身的 MySQL 测试分库，避免其模拟计费状态影响真实服务的后台重试。
    monkeypatch.setattr(config, "CHAT_DB_NAME", "chat_live_test")
    java = "http://127.0.0.1:18123"
    python = "http://127.0.0.1:18000"
    suffix = uuid.uuid4().hex[:12]
    password = "isolated-test-password"
    password_hash = hashlib.sha256(("la-salt-" + password).encode()).hexdigest()
    names = [f"chat_a_{suffix}", f"chat_b_{suffix}"]
    with chat_store.transaction() as cur:
        for username in names:
            cur.execute("INSERT INTO t_user(username,password,nickname) VALUES (%s,%s,%s)",
                        (username, password_hash, username))

    with httpx.Client(timeout=30, trust_env=False) as client:
        def login(username):
            result = client.post(java + "/api/auth/login", json={"username": username, "password": password}).json()
            assert result["code"] == 0, result
            return {"Authorization": "Bearer " + result["data"]["accessToken"]}

        owner, outsider = login(names[0]), login(names[1])
        assert client.get(python + "/api/chat/sessions").status_code == 401
        course = client.post(java + "/api/courses", headers=owner, json={"name": "e2e course"}).json()
        assert course["code"] == 0, course
        course_id = course["data"]["id"]
        kb = client.post(java + f"/api/courses/{course_id}/kb", headers=owner, json={"name": "e2e kb"}).json()
        assert kb["code"] == 0, kb
        kb_id = kb["data"]["id"]

        forbidden = client.post(python + "/api/chat/sessions", headers=outsider,
                                json={"courseId": course_id, "kbId": kb_id})
        assert forbidden.status_code == 403
        created = client.post(python + "/api/chat/sessions", headers=owner,
                              json={"courseId": course_id, "kbId": kb_id, "kbScope": "course"}).json()
        assert created["code"] == 0, created
        session_id = created["data"]["id"]
        denied = client.get(python + f"/api/chat/sessions/{session_id}/messages", headers=outsider).json()
        assert denied["code"] != 0

        streamed = client.post(python + f"/api/chat/sessions/{session_id}/stream", headers=owner,
                               json={"message": "请解释"})
        assert streamed.status_code == 200
        assert streamed.headers["content-type"].startswith("text/event-stream")
        assert "测试" in streamed.text and "回答" in streamed.text and '"sources"' in streamed.text
        history = client.get(python + f"/api/chat/sessions/{session_id}/messages", headers=owner).json()["data"]
        assert [row["role"] for row in history] == ["user", "assistant"]
        assert history[-1]["content"] == "测试回答"

        # 直接连接 Java 8080 的旧客户端通过兼容转发仍可使用同一接口和 SSE。
        legacy_list = client.get(java + "/api/chat/sessions", headers=owner).json()
        assert any(row["id"] == session_id for row in legacy_list["data"])
        legacy_stream = client.post(java + f"/api/chat/sessions/{session_id}/stream", headers=owner,
                                    json={"message": "再解释一次"})
        assert legacy_stream.status_code == 200 and "测试" in legacy_stream.text

        # 真正中途断开 SSE：Python 后台仍完成生成、保存并结算。
        with client.stream("POST", python + f"/api/chat/sessions/{session_id}/stream", headers=owner,
                           json={"message": "断线后继续"}) as disconnected:
            assert disconnected.status_code == 200
            assert any('"delta"' in line for line in disconnected.iter_lines())
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            history = client.get(python + f"/api/chat/sessions/{session_id}/messages", headers=owner).json()["data"]
            if len(history) == 6 and history[-1]["role"] == "assistant":
                break
            time.sleep(0.2)
        else:
            pytest.fail("客户端断开后，答疑未继续保存")

        flashcards = client.post(java + "/api/flashcards/generate", headers=owner,
                                 json={"mode": "chat", "sourceId": session_id}).json()
        assert flashcards["code"] == 0 and flashcards["data"]["created"] == 1, flashcards
        forbidden_cards = client.post(java + "/api/flashcards/generate", headers=outsider,
                                      json={"mode": "chat", "sourceId": session_id}).json()
        assert forbidden_cards["code"] != 0

        with chat_store.transaction() as cur:
            cur.execute("SELECT user_id FROM t_chat_session WHERE id=%s", (session_id,))
            user_id = cur.fetchone()["user_id"]
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            with chat_store.transaction() as cur:
                cur.execute("SELECT balance FROM t_billing_account WHERE user_id=%s", (user_id,))
                balance = cur.fetchone()["balance"]
                cur.execute("SELECT COUNT(*) AS n FROM t_billing_usage WHERE user_id=%s AND status='CONSUMED'", (user_id,))
                consumed = cur.fetchone()["n"]
            if consumed == 3:
                break
            time.sleep(0.2)
        assert consumed == 3 and balance == 7

        assert client.delete(java + f"/api/courses/{course_id}", headers=owner).json()["code"] == 0
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline:
            remaining = client.get(python + "/api/chat/sessions", headers=owner).json()["data"]
            if all(row["id"] != session_id for row in remaining):
                break
            time.sleep(0.25)
        else:
            pytest.fail("课程删除后，Python 未清理对应的答疑会话")
