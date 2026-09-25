import os

import pytest
from app import memory


def test_add_and_recent(tmp_path, monkeypatch):
    monkeypatch.setattr(memory.config, "MEMORY_DIR", str(tmp_path))
    memory.add("s1", "user", "hello")
    memory.add("s1", "assistant", "hi")
    recent = memory.recent("s1", rounds=2)
    assert len(recent) == 2
    assert recent[0]["role"] == "user"


def test_clear(tmp_path, monkeypatch):
    monkeypatch.setattr(memory.config, "MEMORY_DIR", str(tmp_path))
    memory.add("s2", "user", "a")
    memory.clear("s2")
    assert memory.recent("s2") == []


def test_bad_line_skipped(tmp_path, monkeypatch):
    """写入中途崩溃留下的半行 JSON：跳过坏行，而不是让该会话后续所有答疑 500。"""
    monkeypatch.setattr(memory.config, "MEMORY_DIR", str(tmp_path))
    memory.add("s3", "user", "好问题")
    with open(os.path.join(str(tmp_path), "s3.jsonl"), "a", encoding="utf-8") as f:
        f.write('{"role": "user", "con')
    memory.add("s3", "assistant", "好答案")
    rows = memory.recent("s3")
    assert [r["content"] for r in rows] == ["好问题", "好答案"]


def test_compress_keeps_messages_added_during_summarize(tmp_path, monkeypatch):
    """压缩的 LLM 摘要耗时数秒且不持锁：期间并发 add 的新消息不能被重写覆盖掉。"""
    monkeypatch.setattr(memory.config, "MEMORY_DIR", str(tmp_path))
    monkeypatch.setattr(memory, "COMPRESS_THRESHOLD", 4)
    for i in range(4):
        memory.add("c1", "user", f"q{i}")
        memory.add("c1", "assistant", f"a{i}")

    def summarizer_adding_row(old_summary, rows_text):
        memory.add("c1", "user", "压缩期间的新消息")
        return "摘要"

    memory.maybe_compress("c1", summarizer_adding_row)
    rows = memory._read_all("c1")
    assert memory.summary("c1") == "摘要"
    assert len(rows) == 5  # 保留后半 4 条 + 压缩期间新增 1 条
    assert rows[0]["content"] == "q2"
    assert rows[-1]["content"] == "压缩期间的新消息"


def test_compress_abandons_when_prefix_changed(tmp_path, monkeypatch):
    """两轮压缩并发：迟到的一轮发现前缀已被对方压掉，直接放弃，不覆盖对方成果。"""
    monkeypatch.setattr(memory.config, "MEMORY_DIR", str(tmp_path))
    monkeypatch.setattr(memory, "COMPRESS_THRESHOLD", 4)
    for i in range(8):
        memory.add("c2", "user", f"m{i}")
    stale = memory._read_all("c2")  # 迟到压缩手里的旧快照（8 条）
    memory.maybe_compress("c2", lambda old, text: "对方摘要")  # 并发的先到者：8 → 4

    real_read_all = memory._read_all

    def stale_first(session_id, rounds=None):
        return stale

    monkeypatch.setattr(memory, "_read_all", stale_first)
    memory.maybe_compress("c2", lambda old, text: "迟到摘要")

    assert memory.summary("c2") == "对方摘要"
    rows = real_read_all("c2")
    assert len(rows) == 4
    assert rows[0]["content"] == "m4"


def test_compress_does_not_resurrect_cleared_session(tmp_path, monkeypatch):
    """压缩期间会话记忆被清空（如删会话联动）：不复活旧数据。"""
    monkeypatch.setattr(memory.config, "MEMORY_DIR", str(tmp_path))
    monkeypatch.setattr(memory, "COMPRESS_THRESHOLD", 4)
    for i in range(4):
        memory.add("c3", "user", f"q{i}")
        memory.add("c3", "assistant", f"a{i}")

    def clearing_summarizer(old_summary, rows_text):
        memory.clear("c3")
        return "摘要"

    memory.maybe_compress("c3", clearing_summarizer)
    assert memory._read_all("c3") == []
    assert memory.summary("c3") == ""


def test_user_profile_roundtrip_and_truncation(tmp_path, monkeypatch):
    monkeypatch.setattr(memory.config, "MEMORY_DIR", str(tmp_path))
    assert memory.user_profile(9) == ""
    memory.save_user_profile(9, "学生喜欢先看例子再听原理")
    assert memory.user_profile("9") == "学生喜欢先看例子再听原理"
    memory.save_user_profile(9, "长" * 1000)
    assert len(memory.user_profile(9)) == memory.PROFILE_MAX_CHARS
