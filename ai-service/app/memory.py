"""会话记忆：按 sessionId 持久化对话历史（JSONL）+ 长会话摘要压缩 + 用户级长期画像。

记忆分层：
- 会话内：最近 HISTORY_ROUNDS 轮原文保留；超出部分自动压缩为「对话摘要」（滚动摘要，追加式），
  组装上下文时以 system 消息形式注入，实现长会话不丢失关键信息。
- 跨会话：按 userId 维护「学生长期画像」（学习目标、偏好、稳定薄弱点），
  由 chains 在每轮答疑后异步提炼合并（见 chains._extract_profile），
  组装上下文时同样以 system 注入，实现换会话不遗忘学生。
- 并发策略：文件读写均持全局锁（纯 IO，临界区极短）；压缩的 LLM 摘要在锁外进行，
  落盘前重读对账，把压缩期间并发追加的新消息原样保留，避免读-改-写覆盖丢消息。
- 摘要由 summarizer 回调生成（依赖注入，避免与 chains 循环引用）。
"""
import json
import logging
import os
import threading

from app import config

logger = logging.getLogger("ai-service.memory")

_lock = threading.Lock()

# 触发摘要压缩的阈值：历史消息超过该条数时，把最旧的一半压缩进摘要
COMPRESS_THRESHOLD = int(os.getenv("AI_COMPRESS_THRESHOLD", "16"))
# 用户画像文件长度上限：读/写时硬截断，防止注入提示词无限膨胀
PROFILE_MAX_CHARS = 600


def _safe_name(value) -> str:
    safe = "".join(c for c in str(value) if c.isalnum() or c in "-_") or "default"
    return safe


def _file(session_id: str) -> str:
    return os.path.join(config.MEMORY_DIR, f"{_safe_name(session_id)}.jsonl")


def _summary_file(session_id: str) -> str:
    return os.path.join(config.MEMORY_DIR, f"{_safe_name(session_id)}.summary.txt")


def _profile_file(user_id) -> str:
    return os.path.join(config.MEMORY_DIR, f"u_{_safe_name(user_id)}.profile.txt")


def add(session_id: str, role: str, content: str) -> None:
    if not session_id:
        return
    with _lock:
        path = _file(session_id)
        needs_newline = False
        if os.path.exists(path) and os.path.getsize(path) > 0:
            with open(path, "rb") as f:
                f.seek(-1, os.SEEK_END)
                needs_newline = f.read(1) != b"\n"
        with open(path, "a", encoding="utf-8") as f:
            if needs_newline:
                # 上次写入中途崩溃没留下换行符：先补上，避免坏行与本次记录拼成一行
                f.write("\n")
            f.write(json.dumps({"role": role, "content": content}, ensure_ascii=False) + "\n")


def _read_all_unlocked(session_id: str) -> list[dict]:
    path = _file(session_id)
    if not os.path.exists(path):
        return []
    rows = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError:
                # 写入中途崩溃留下的半行：跳过坏行而不是让整个会话的答疑 500
                logger.warning("会话 %s 的记忆文件存在坏行，已跳过", session_id)
    return rows


def _read_all(session_id: str) -> list[dict]:
    if not session_id:
        return []
    with _lock:
        return _read_all_unlocked(session_id)


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
    摘要要调 LLM（耗时数秒），全程不持锁；落盘前做前缀对账：add 只会尾部追加、
    只有压缩会改动前缀，压缩期间并发追加的新消息原样保留；若期间另一轮并发压缩
    已生效（前缀已变短），本轮直接放弃，避免互相覆盖。
    """
    rows = _read_all(session_id)
    if len(rows) < COMPRESS_THRESHOLD:
        return
    half = len(rows) // 2
    old = rows[:half]
    rows_text = "\n".join(
        ("用户" if r.get("role") == "user" else "助手") + ": " + str(r.get("content", ""))[:200]
        for r in old
    )
    try:
        new_summary = summarizer(summary(session_id), rows_text)
    except Exception:
        return  # 摘要失败不阻塞主流程，下轮再试
    with _lock:
        if not os.path.exists(_file(session_id)):
            return  # 压缩期间会话记忆被清空（如删会话），不要复活旧数据
        current = _read_all_unlocked(session_id)
        if current[:half] != old:
            return  # 期间另一轮并发压缩已生效，本轮放弃
        with open(_summary_file(session_id), "w", encoding="utf-8") as f:
            f.write(new_summary)
        with open(_file(session_id), "w", encoding="utf-8") as f:
            for r in current[half:]:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")


def user_profile(user_id) -> str:
    """该学生的长期画像（跨会话记忆，无则空串），读时硬截断防提示词膨胀。"""
    if not user_id:
        return ""
    path = _profile_file(user_id)
    if not os.path.exists(path):
        return ""
    with _lock:
        with open(path, "r", encoding="utf-8") as f:
            return f.read().strip()[:PROFILE_MAX_CHARS]


def save_user_profile(user_id, text: str) -> None:
    if not user_id or not (text or "").strip():
        return
    with _lock:
        with open(_profile_file(user_id), "w", encoding="utf-8") as f:
            f.write(text.strip()[:PROFILE_MAX_CHARS])


def clear(session_id: str) -> None:
    if not session_id:
        return
    with _lock:
        for path in (_file(session_id), _summary_file(session_id)):
            if os.path.exists(path):
                os.remove(path)
