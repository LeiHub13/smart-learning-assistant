"""RAG 检索链路测试：用本地假 Java 内部 API 真实走一遍拉取→向量化→建索引→召回。"""
import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlparse

import pytest

from app import config, embeddings, retriever

CHUNKS = [
    {"chunkId": 1, "docId": 10, "kbId": 100, "content": "HashMap 基于数组加链表加红黑树实现，链表过长会转红黑树。"},
    {"chunkId": 2, "docId": 10, "kbId": 100, "content": "synchronized 与 ReentrantLock 都可以实现 Java 线程同步。"},
    {"chunkId": 3, "docId": 11, "kbId": 100, "content": "MySQL InnoDB 索引采用 B+ 树结构，支持事务与行级锁。"},
    {"chunkId": 4, "docId": 11, "kbId": 200, "content": "TCP 三次握手用于建立可靠的传输连接。"},
]


class _Handler(BaseHTTPRequestHandler):
    def do_GET(self):  # noqa: N802
        parsed = urlparse(self.path)
        if parsed.path != "/internal/tools/chunks":
            self.send_error(404)
            return
        if self.headers.get("X-Internal-Token") != self.server.expected_token:
            self.send_error(403)
            return
        q = parse_qs(parsed.query)
        kb_id = q.get("kbId", [None])[0]
        doc_id = q.get("docId", [None])[0]
        cursor = int(q.get("cursor", ["0"])[0])
        rows = [c for c in CHUNKS
                if int(c["chunkId"]) > cursor
                and (kb_id is None or str(c["kbId"]) == kb_id)
                and (doc_id is None or str(c["docId"]) == doc_id)]
        rows.sort(key=lambda c: c["chunkId"])
        body = json.dumps({"code": 0, "data": rows[:1]}).encode("utf-8")  # 每页 1 条，测分页
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_args):
        pass


@pytest.fixture
def java_stub(tmp_path, monkeypatch):
    server = HTTPServer(("127.0.0.1", 0), _Handler)
    # 固定住握手期约定的 token，测试里改动 config 才能验证鉴权真的生效
    server.expected_token = config.JAVA_TOOL_TOKEN
    threading.Thread(target=server.serve_forever, daemon=True).start()
    monkeypatch.setattr(config, "JAVA_TOOL_BASE", f"http://127.0.0.1:{server.server_port}")
    monkeypatch.setattr(config, "RAG_CHUNK_PAGE_SIZE", 1)
    monkeypatch.setattr(retriever, "_client", None)
    monkeypatch.setattr(config, "CHROMA_DIR", str(tmp_path / "chroma"))
    yield server
    server.shutdown()


def test_hash_embedding_is_stable_and_normalized():
    v1 = embeddings.embed("HashMap 原理")
    v2 = embeddings.embed("HashMap 原理")
    assert v1 == v2, "hash 向量必须跨调用稳定"
    assert len(v1) == 128
    assert abs(sum(v * v for v in v1) - 1.0) < 1e-6


def test_index_document_pulls_all_chunks_via_pagination(java_stub):
    indexed = retriever.index_document(kb_id=100)
    assert indexed == 3, "kbId=100 下有 3 个 chunk，分页要全部拉到"
    assert retriever.stats()["vectors"] == 3


def test_search_returns_most_relevant_first(java_stub):
    retriever.index_document()
    hits = retriever.search("HashMap 底层数据结构", kb_id=100, top_k=2)
    assert hits, "应能召回到内容"
    assert hits[0]["chunkId"] == 1, hits
    assert "HashMap" in hits[0]["content"]
    assert hits[0]["score"] >= hits[-1]["score"]


def test_search_filters_by_kb(java_stub):
    retriever.index_document()
    hits = retriever.search("TCP 三次握手", kb_id=100, top_k=5)
    assert all(h["kbId"] == 100 for h in hits), hits
    hits2 = retriever.search("TCP 三次握手", kb_id=200, top_k=5)
    assert hits2 and hits2[0]["chunkId"] == 4


def test_remove_chunks_and_document(java_stub):
    retriever.index_document()
    retriever.remove_chunks([1])
    assert retriever.stats()["vectors"] == 3
    retriever.remove_document(11)
    remaining = {h["chunkId"] for h in retriever.search("索引", top_k=10)}
    assert 3 not in remaining


def test_java_token_required(java_stub, monkeypatch):
    monkeypatch.setattr(config, "JAVA_TOOL_TOKEN", "wrong-token")
    with pytest.raises(Exception):
        retriever._fetch_java_chunks(kb_id=100)


def test_retrieve_endpoint_contract(java_stub):
    """/ai/retrieve 的响应形状是 Java 侧 PythonRagClient 的解析依据，锁住字段名与鉴权。"""
    from fastapi.testclient import TestClient

    from app import main

    retriever.index_document()
    client = TestClient(main.app)  # 不进上下文管理器：跳过 lifespan（避免拉起 MCP 子进程）
    token = {"X-Internal-Token": config.JAVA_TOOL_TOKEN}

    assert client.post("/ai/retrieve", json={"query": "HashMap", "kbId": 100, "topK": 2}).status_code == 403
    assert client.get("/ai/rag/stats").status_code == 403

    resp = client.post("/ai/retrieve", json={"query": "HashMap", "kbId": 100, "topK": 2}, headers=token)
    assert resp.status_code == 200
    hits = resp.json()["hits"]
    assert hits and {"chunkId", "content", "score"} <= set(hits[0])
    assert hits[0]["chunkId"] == 1

    stats = client.get("/ai/rag/stats", headers=token).json()
    assert stats["vectors"] == 4 and stats["provider"] == "hash"

    deleted = client.post("/ai/index/delete", json={"docId": 11}, headers=token)
    assert deleted.status_code == 200
    assert client.post("/ai/retrieve", json={"query": "HashMap", "kbId": 100}, headers=token).json()["hits"]
