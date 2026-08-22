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
