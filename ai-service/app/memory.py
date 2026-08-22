"""会话记忆：按 sessionId 持久化对话历史（JSONL）+ 长会话摘要压缩。

记忆策略：
- 最近 HISTORY_ROUNDS 轮原文保留；
- 超出部分自动压缩为「对话摘要」（滚动摘要，追加式），
  组装上下文时以 system 消息形式注入，实现长会话不丢失关键信息。
- 摘要由 summarizer 回调生成（依赖注入，避免与 chains 循环引用）。
"""
import json
import os
import threading

from app import config

_lock = threading.Lock()

# 触发摘要压缩的阈值：历史消息超过该条数时，把最旧的一半压缩进摘要
COMPRESS_THRESHOLD = int(os.getenv("AI_COMPRESS_THRESHOLD", "16"))


def _file(session_id: str) -> str:
    safe = "".join(c for c in str(session_id) if c.isalnum() or c in "-_") or "default"
    return os.path.join(config.MEMORY_DIR, f"{safe}.jsonl")


def _summary_file(session_id: str) -> str:
    safe = "".join(c for c in str(session_id) if c.isalnum() or c in "-_") or "default"
    return os.path.join(config.MEMORY_DIR, f"{safe}.summary.txt")


def add(session_id: str, role: str, content: str) -> None:
    if not session_id:
        return
    with _lock:
        with open(_file(session_id), "a", encoding="utf-8") as f:
            f.write(json.dumps({"role": role, "content": content}, ensure_ascii=False) + "\n")


def _read_all(session_id: str) -> list[dict]:
    path = _file(session_id)
    if not session_id or not os.path.exists(path):
        return []
    with _lock:
        with open(path, "r", encoding="utf-8") as f:
            return [json.loads(line) for line in f if line.strip()]


def recent(session_id: str, rounds: int = None) -> list[dict]:
    """最近 N 轮（user+assistant 对）历史消息。"""
    rows = _read_all(session_id)
    rounds = rounds or config.HISTORY_ROUNDS
    return rows[-rounds * 2:]


def summary(session_id: str) -> str:
    """当前会话的滚动摘要（无则空串）。"""
    path = _summary_file(session_id)
    if not session_id or not os.path.exists(path):
        return ""
    with _lock:
        with open(path, "r", encoding="utf-8") as f:
            return f.read().strip()


def maybe_compress(session_id: str, summarizer) -> None:
    """历史过长时压缩：最旧的一半消息 → 摘要，原文只保留较新的一半。

    summarizer: callable(old_summary: str, rows_text: str) -> str
    """
    rows = _read_all(session_id)
    if len(rows) < COMPRESS_THRESHOLD:
        return
    half = len(rows) // 2
    old, keep = rows[:half], rows[half:]
    rows_text = "\n".join(
        ("用户" if r.get("role") == "user" else "助手") + ": " + str(r.get("content", ""))[:200]
        for r in old
    )
    try:
        new_summary = summarizer(summary(session_id), rows_text)
    except Exception:
        return  # 摘要失败不阻塞主流程，下轮再试
    with _lock:
        with open(_summary_file(session_id), "w", encoding="utf-8") as f:
            f.write(new_summary)
        with open(_file(session_id), "w", encoding="utf-8") as f:
            for r in keep:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")


def clear(session_id: str) -> None:
    if not session_id:
        return
    for path in (_file(session_id), _summary_file(session_id)):
        if os.path.exists(path):
            os.remove(path)
