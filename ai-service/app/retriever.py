"""RAG 检索器：Chroma 持久化向量索引 + 从 Java 内部 API 拉取 chunk 建索引。

数据归属：chunk 正文的唯一数据源仍是 Java 侧 t_chunk。本模块只是它的派生索引，
索引内容可通过 /ai/index 全量重建（rebuild_all）。

Java 侧契约（X-Internal-Token 鉴权）：
GET /internal/tools/chunks?kbId=&docId=&cursor=&limit=
  -> {code:0, data:[{chunkId, docId, kbId, content}]}   按 chunkId 升序游标分页
"""
import json
import logging
import threading
import urllib.parse
import urllib.request

from app import config, embeddings

logger = logging.getLogger("ai-service.retriever")

_client = None
_lock = threading.Lock()


def _collection():
    global _client
    import chromadb

    with _lock:
        if _client is None:
            _client = chromadb.PersistentClient(path=config.CHROMA_DIR)
            logger.info("Chroma 已连接: %s", config.CHROMA_DIR)
    return _client.get_or_create_collection(
        name=config.RAG_COLLECTION,
        metadata={"hnsw:space": "cosine"},
    )


def _fetch_java_chunks(kb_id=None, doc_id=None, cursor=0, limit=None):
    """按游标向 Java 拉一页 chunk。"""
    params = {"cursor": cursor, "limit": limit or config.RAG_CHUNK_PAGE_SIZE}
    if kb_id is not None:
        params["kbId"] = kb_id
    if doc_id is not None:
        params["docId"] = doc_id
    url = config.JAVA_TOOL_BASE.rstrip("/") + "/internal/tools/chunks?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"X-Internal-Token": config.JAVA_TOOL_TOKEN})
    with urllib.request.urlopen(req, timeout=30) as resp:
        body = json.loads(resp.read().decode("utf-8"))
    if body.get("code") != 0:
        raise RuntimeError("拉取 chunk 失败: " + str(body.get("message")))
    return body.get("data") or []


def index_chunks(rows: list[dict]) -> int:
    """把 chunk 向量化并 upsert 进 Chroma（幂等，按 chunkId 覆盖）。"""
    rows = [r for r in rows if r.get("content")]
    if not rows:
        return 0
    col = _collection()
    col.upsert(
        ids=[str(r["chunkId"]) for r in rows],
        embeddings=[embeddings.embed(r["content"]) for r in rows],
        documents=[r["content"] for r in rows],
        metadatas=[{"kbId": int(r.get("kbId") or 0), "docId": int(r.get("docId") or 0)}
                   for r in rows],
    )
    return len(rows)


def index_document(kb_id=None, doc_id=None) -> int:
    """索引一个文档（或整个知识库）的全部 chunk。"""
    total = 0
    cursor = 0
    while True:
        rows = _fetch_java_chunks(kb_id=kb_id, doc_id=doc_id, cursor=cursor)
        if not rows:
            break
        total += index_chunks(rows)
        last = max(int(r["chunkId"]) for r in rows)
        if last <= cursor:
            break
        cursor = last
        if len(rows) < config.RAG_CHUNK_PAGE_SIZE:
            break
    logger.info("索引完成 kbId=%s docId=%s chunks=%s", kb_id, doc_id, total)
    return total


def rebuild_all() -> int:
    """全量重建：清空集合后从 t_chunk 重新拉取。"""
    col = _collection()
    try:
        col.delete(where={})
    except Exception:  # noqa: BLE001
        # 部分版本不支持 where={} 清空，直接删集合重建
        global _client
        import chromadb

        with _lock:
            if _client is None:
                _client = chromadb.PersistentClient(path=config.CHROMA_DIR)
            try:
                _client.delete_collection(config.RAG_COLLECTION)
            except Exception as e:  # noqa: BLE001
                logger.debug("删除集合失败（可能不存在）: %s", e)
        _collection()
    return index_document()


def remove_chunks(chunk_ids: list[int]) -> int:
    if not chunk_ids:
        return 0
    _collection().delete(ids=[str(i) for i in chunk_ids])
    return len(chunk_ids)


def remove_document(doc_id: int) -> int:
    _collection().delete(where={"docId": {"$eq": int(doc_id)}})
    return 1


def search(query: str, kb_id=None, top_k: int = None, candidate_k: int = None):
    """向量召回。返回 [{chunkId, docId, kbId, content, score}]，score 越大越相关。"""
    if not query or not query.strip():
        return []
    col = _collection()
    total = col.count()
    if total == 0:
        return []
    k = min(candidate_k or top_k or config.RAG_TOP_K, total)
    where = {"kbId": {"$eq": int(kb_id)}} if kb_id is not None else None
    res = col.query(
        query_embeddings=[embeddings.embed(query)],
        n_results=k,
        where=where,
        include=["documents", "metadatas", "distances"],
    )
    docs = (res.get("documents") or [[]])[0]
    metas = (res.get("metadatas") or [[]])[0]
    dists = (res.get("distances") or [[]])[0]
    ids = (res.get("ids") or [[]])[0]
    hits = []
    for i, cid in enumerate(ids):
        meta = metas[i] or {}
        hits.append({
            "chunkId": int(cid),
            "docId": meta.get("docId"),
            "kbId": meta.get("kbId"),
            "content": docs[i] if i < len(docs) else "",
            # Chroma cosine 返回距离，转成与原 Java 余弦相似度同向的分数
            "score": 1 - dists[i] if i < len(dists) else 0.0,
        })
    return hits


def stats() -> dict:
    info = embeddings.provider_info()
    try:
        info["vectors"] = _collection().count()
    except Exception as e:  # noqa: BLE001
        logger.warning("读取向量计数失败: %s", e)
        info["vectors"] = -1
    info["collection"] = config.RAG_COLLECTION
    info["chromaDir"] = config.CHROMA_DIR
    return info
