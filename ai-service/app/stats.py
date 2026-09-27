"""LLM 调用观测：按场景记录调用次数 / 成功率 / 平均耗时 / token 用量，落 JSONL 文件。

数据文件：data/stats/calls.jsonl（每次调用一行）
读取接口：GET /ai/stats 返回聚合结果（见 main.py）。
tokens：优先取模型返回的真实 usage；流式无 usage 时按字符数估算（约 3 字符/token）。
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


def record(scene: str, success: bool, latency_ms: int, tokens: int = 0, tokens_estimated: bool = False) -> None:
    row = {"ts": int(time.time()), "scene": scene, "ok": success, "ms": latency_ms,
           "tokens": max(0, int(tokens)), "tk_est": bool(tokens_estimated)}
    try:
        with _lock:
            with open(_FILE, "a", encoding="utf-8") as f:
                f.write(json.dumps(row, ensure_ascii=False) + "\n")
    except OSError:
        pass  # 观测失败不影响主流程


def aggregate(hours: int = 24, days: int = 0) -> dict:
    """聚合：最近 N 小时调用量/成功率/token，以及可选的按天趋势（days>0 时）。

    按天桶以本地日期划分；为控制内存，单次最多读取最近 20000 行。
    """
    since = time.time() - hours * 3600
    day_start = None
    if days > 0:
        import datetime
        today = datetime.date.today()
        day_start = time.mktime((today - datetime.timedelta(days=days - 1)).timetuple())
    by_scene = defaultdict(lambda: {"calls": 0, "failures": 0, "totalMs": 0, "tokens": 0})
    daily = defaultdict(lambda: {"calls": 0, "failures": 0, "tokens": 0})
    total = 0
    failures = 0
    tokens_total = 0
    if os.path.exists(_FILE):
        with _lock:
            with open(_FILE, "r", encoding="utf-8") as f:
                lines = f.readlines()
        for line in lines[-20000:]:
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                continue
            ts = row.get("ts", 0)
            if ts < since:
                continue
            tk = int(row.get("tokens", 0) or 0)
            total += 1
            tokens_total += tk
            ok = bool(row.get("ok"))
            if not ok:
                failures += 1
            scene = row.get("scene", "unknown")
            b = by_scene[scene]
            b["calls"] += 1
            b["failures"] += 0 if ok else 1
            b["totalMs"] += int(row.get("ms", 0))
            b["tokens"] += tk
            if day_start is not None and ts >= day_start:
                import datetime
                d = datetime.datetime.fromtimestamp(ts).strftime("%Y-%m-%d")
                db = daily[d]
                db["calls"] += 1
                db["failures"] += 0 if ok else 1
                db["tokens"] += tk
    scenes = {
        s2: {
            "calls": b["calls"],
            "failures": b["failures"],
            "avgMs": round(b["totalMs"] / b["calls"]) if b["calls"] else 0,
            "tokens": b["tokens"],
        }
        for s2, b in sorted(by_scene.items())
    }
    result = {
        "hours": hours,
        "totalCalls": total,
        "failures": failures,
        "successRate": round((total - failures) / total * 100, 1) if total else 100.0,
        "totalTokens": tokens_total,
        "byScene": scenes,
    }
    if day_start is not None:
        result["daily"] = [
            {"date": d, "calls": v["calls"], "failures": v["failures"], "tokens": v["tokens"]}
            for d, v in sorted(daily.items())
        ]
        result["days"] = days
    return result
