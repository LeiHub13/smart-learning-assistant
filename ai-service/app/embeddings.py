"""向量化：文本 -> 稠密向量。RAG 索引与检索的共用底座。

provider 由 RAG_EMBEDDING_PROVIDER 切换：
- hash       字符 n-gram 哈希（128 维，离线零依赖，移植自原 Java HashEmbeddingService）
- dashscope  通义 text-embedding-v3（OpenAI 兼容 /embeddings）

注意：hash 版必须跨进程稳定，否则重启后新算的向量与库里旧向量不可比。
Python 内置 hash() 受 PYTHONHASHSEED 随机化，这里改用 zlib.crc32。
"""
import json
import logging
import threading
import urllib.request
import zlib

from app import config

logger = logging.getLogger("ai-service.embeddings")

HASH_DIM = 128

_lock = threading.Lock()
_dashscope_cache: dict[str, list[float]] = {}


def dim() -> int:
    return HASH_DIM if config.EMBEDDING_PROVIDER == "hash" else -1


def embed(text: str) -> list[float]:
    provider = config.EMBEDDING_PROVIDER
    if provider == "dashscope":
        return _embed_dashscope(text)
    if provider == "hash":
        return _embed_hash(text)
    raise RuntimeError(f"未知的 RAG_EMBEDDING_PROVIDER: {provider}（支持 hash / dashscope）")


def embed_many(texts: list[str]) -> list[list[float]]:
    return [embed(t) for t in texts]


def _embed_hash(text: str) -> list[float]:
    vec = [0.0] * HASH_DIM
    if not text or not text.strip():
        return vec
    norm = "".join(text.lower().split())
    grams = []
    for i, ch in enumerate(norm):
        grams.append(ch)
        if i + 1 < len(norm):
            grams.append(norm[i:i + 2])
    for g in grams:
        h = zlib.crc32(g.encode("utf-8")) & 0xFFFFFFFF
        vec[h % HASH_DIM] += 1.0
    norm2 = sum(v * v for v in vec) ** 0.5
    if norm2 > 0:
        vec = [v / norm2 for v in vec]
    return vec


def _embed_dashscope(text: str) -> list[float]:
    if not text or not text.strip():
        raise RuntimeError("Embedding 输入为空")
    with _lock:
        hit = _dashscope_cache.get(text)
    if hit is not None:
        return hit
    if not config.EMBEDDING_API_KEY:
        raise RuntimeError("使用 dashscope embedding 但未配置 RAG_EMBEDDING_API_KEY / AI_API_KEY")
    url = config.EMBEDDING_BASE_URL.rstrip("/") + "/embeddings"
    body = json.dumps({"model": config.EMBEDDING_MODEL, "input": [text]}).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=body,
        headers={
            "Content-Type": "application/json",
            "Authorization": "Bearer " + config.EMBEDDING_API_KEY,
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
    except Exception as e:  # noqa: BLE001
        raise RuntimeError(f"Embedding 调用失败: {e}") from e
    data = payload.get("data") or []
    if not data or not data[0].get("embedding"):
        raise RuntimeError("Embedding 接口未返回向量")
    vec = [float(v) for v in data[0]["embedding"]]
    with _lock:
        if len(_dashscope_cache) > 20000:
            _dashscope_cache.clear()
        _dashscope_cache[text] = vec
    return vec


def provider_info() -> dict:
    return {
        "provider": config.EMBEDDING_PROVIDER,
        "model": "hash-" + str(HASH_DIM) if config.EMBEDDING_PROVIDER == "hash" else config.EMBEDDING_MODEL,
    }
