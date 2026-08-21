"""会话记忆：按 sessionId 持久化对话历史（JSONL），供 RAG 答疑链拼装上下文。"""
import json
import os
import threading

from app import config

_lock = threading.Lock()


def _file(session_id: str) -> str:
    safe = "".join(c for c in str(session_id) if c.isalnum() or c in "-_") or "default"
    return os.path.join(config.MEMORY_DIR, f"{safe}.jsonl")


def add(session_id: str, role: str, content: str) -> None:
    if not session_id:
        return
    with _lock:
        with open(_file(session_id), "a", encoding="utf-8") as f:
            f.write(json.dumps({"role": role, "content": content}, ensure_ascii=False) + "\n")


def recent(session_id: str, rounds: int = None) -> list[dict]:
    """最近 N 轮（user+assistant 对）历史消息。"""
    if not session_id or not os.path.exists(_file(session_id)):
        return []
    rounds = rounds or config.HISTORY_ROUNDS
    with _lock:
        with open(_file(session_id), "r", encoding="utf-8") as f:
            rows = [json.loads(line) for line in f if line.strip()]
    return rows[-rounds * 2:]


def clear(session_id: str) -> None:
    if not session_id:
        return
    path = _file(session_id)
    if os.path.exists(path):
        os.remove(path)