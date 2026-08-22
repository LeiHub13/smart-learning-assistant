"""LLM 调用观测：按场景记录调用次数 / 成功率 / 平均耗时，落 JSONL 文件。

数据文件：data/stats/calls.jsonl（每次调用一行）
读取接口：GET /ai/stats 返回聚合结果（见 main.py）。
"""
import json
import os
import threading
import time
from collections import defaultdict

_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "data", "stats")
os.makedirs(_DIR, exist_ok=True)
_FILE = os.path.join(_DIR, "calls.jsonl")
_lock = threading.Lock()


def record(scene: str, success: bool, latency_ms: int) -> None:
    row = {"ts": int(time.time()), "scene": scene, "ok": success, "ms": latency_ms}
    try:
        with _lock:
            with open(_FILE, "a", encoding="utf-8") as f:
                f.write(json.dumps(row, ensure_ascii=False) + "\n")
    except OSError:
        pass  # 观测失败不影响主流程


def aggregate(hours: int = 24) -> dict:
    """最近 N 小时聚合：总调用数、成功率、按场景分组的次数与平均耗时。"""
    since = time.time() - hours * 3600
    by_scene = defaultdict(lambda: {"calls": 0, "failures": 0, "totalMs": 0})
    total = 0
    failures = 0
    if os.path.exists(_FILE):
        with _lock:
            with open(_FILE, "r", encoding="utf-8") as f:
                lines = f.readlines()
        for line in lines[-5000:]:
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                continue
            if row.get("ts", 0) < since:
                continue
            total += 1
            ok = bool(row.get("ok"))
            if not ok:
                failures += 1
            b = by_scene[row.get("scene", "unknown")]
            b["calls"] += 1
            b["failures"] += 0 if ok else 1
            b["totalMs"] += int(row.get("ms", 0))
    scenes = {
        s: {
            "calls": b["calls"],
            "failures": b["failures"],
            "avgMs": round(b["totalMs"] / b["calls"]) if b["calls"] else 0,
        }
        for s, b in sorted(by_scene.items())
    }
    return {
        "hours": hours,
        "totalCalls": total,
        "failures": failures,
        "successRate": round((total - failures) / total * 100, 1) if total else 100.0,
        "byScene": scenes,
    }
