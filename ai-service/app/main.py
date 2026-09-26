"""AI 服务入口：FastAPI + SSE 流式接口，供 Java 骨架调用。

接口约定（与 Java 端 PythonAIChatModel / PythonRagClient 对齐）：
- GET  /ai/health          健康检查，返回模型配置摘要
- POST /ai/complete        非流式生成 {scene, question, chunks?, sessionId?, kbId?, kbIds?, note?} -> {content, sources}
- POST /ai/stream          流式生成（SSE）{scene, question, chunks?, sessionId?, kbId?, kbIds?, note?}
                           -> data: {"delta": "..."} ... data: {"done": true, "sources": "1,2"}
- GET  /ai/history/{id}    会话记忆
- POST /ai/memory/clear    清空某会话记忆 {sessionId}（Java 删会话时联动调用，需内部 token）
- GET  /ai/stats           LLM 调用观测
RAG 端点（需 X-Internal-Token 头，仅供 Java 内部调用）：
- POST /ai/retrieve        向量召回 {query, kbId?, kbIds?, topK?} -> {hits:[{chunkId, content, score}]}
- POST /ai/index           建索引 {kbId?, docId?, all?} -> {indexed}
- POST /ai/index/delete    删索引 {chunkIds?|docId?}
- GET  /ai/rag/stats       向量库状态
文档解析端点（需 X-Internal-Token 头，仅供 Java 内部调用）：
- POST /ai/parse           multipart file -> {chunks:[...], textChars, fileName}  解析原始文件并切块
- POST /ai/chunk           {content} -> {chunks:[...]}                            纯文本切块
"""
import json
import logging
import sys
from contextlib import asynccontextmanager
from pathlib import Path

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from fastapi import FastAPI, File, Header, HTTPException, UploadFile
from pydantic import BaseModel, Field
from sse_starlette.sse import EventSourceResponse

from app import chains, config

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
logger = logging.getLogger("ai-service")


@asynccontextmanager
async def lifespan(_app: FastAPI):
    # 启动时预热 MCP 外部工具（未配置/失败均不影响启动）
    if config.MCP_SERVERS:
        from app import agent
        await agent.init_mcp_tools()
    yield


app = FastAPI(title="AI Service (langchain)", version="1.0.0", lifespan=lifespan)

SCENES = {"rag_qa", "free", "agent", "lecture", "questions", "review", "advice",
          "rewrite", "rerank", "plan", "report", "recommend"}


class CompleteRequest(BaseModel):
    scene: str
    question: str
    chunks: list | None = Field(default=None)
    sessionId: str | None = Field(default=None)
    userId: int | None = Field(default=None)
    kbId: int | None = Field(default=None)
    kbIds: list[int] | None = Field(default=None)
    courseId: int | None = Field(default=None)
    note: str | None = Field(default=None)
    kbName: str | None = Field(default=None)
    kbScope: str | None = Field(default=None)


class StreamRequest(CompleteRequest):
    pass


class IndexRequest(BaseModel):
    kbId: int | None = Field(default=None)
    docId: int | None = Field(default=None)
    all: bool = Field(default=False)


class DeleteIndexRequest(BaseModel):
    chunkIds: list[int] | None = Field(default=None)
    docId: int | None = Field(default=None)


class ChunkRequest(BaseModel):
    content: str


class RetrieveRequest(BaseModel):
    query: str
    kbId: int | None = Field(default=None)
    kbIds: list[int] | None = Field(default=None)
    topK: int | None = Field(default=None)


class MemoryClearRequest(BaseModel):
    sessionId: str


def check_internal_token(x_internal_token: str | None) -> None:
    """RAG 索引/检索端点仅供 Java 骨架内部调用，用与回调通道同一把内部 token。"""
    if x_internal_token != config.JAVA_TOOL_TOKEN:
        raise HTTPException(403, "内部接口鉴权失败")


@app.get("/ai/health")
def health():
    from app import agent
    return {
        "status": "ok",
        "provider": config.PROVIDER,
        "model": config.MODEL,
        "baseUrl": config.BASE_URL,
        "agentScene": True,
        "mcp": agent.mcp_status(),
    }


@app.post("/ai/complete")
def complete(req: CompleteRequest):
    if req.scene not in SCENES:
        raise HTTPException(400, f"未知场景: {req.scene}")
    meta: dict = {}
    try:
        content = chains.complete(req.scene, req.question, req.chunks, req.sessionId,
                                  user_id=req.userId, kb_id=req.kbId, kb_ids=req.kbIds or None,
                                  note=req.note, meta=meta,
                                  course_id=req.courseId or None,
                                  kb_name=req.kbName, kb_scope=req.kbScope)
        return {"content": content, "sources": meta.get("sources", "")}
    except RuntimeError as e:
        logger.error("complete 失败: %s", e)
        raise HTTPException(502, str(e)) from e


@app.post("/ai/stream")
async def stream(req: StreamRequest):
    if req.scene not in SCENES:
        raise HTTPException(400, f"未知场景: {req.scene}")

    meta: dict = {}

    def gen():
        try:
            for delta in chains.stream(req.scene, req.question, req.chunks, req.sessionId,
                                       user_id=req.userId, kb_id=req.kbId, kb_ids=req.kbIds or None,
                                       note=req.note, meta=meta,
                                       course_id=req.courseId or None,
                                       kb_name=req.kbName, kb_scope=req.kbScope):
                yield {"event": "message", "data": json.dumps({"delta": delta}, ensure_ascii=False)}
            # sources：本服务自行检索时产出的引用编号，由 Java 落库
            yield {"event": "message",
                   "data": json.dumps({"done": True, "sources": meta.get("sources", "")})}
        except RuntimeError as e:
            logger.error("stream 失败: %s", e)
            yield {"event": "error", "data": json.dumps({"error": str(e)})}

    return EventSourceResponse(gen(), ping=None)


# ===== RAG 检索与索引端点（仅供 Java 骨架内部调用）=====

@app.post("/ai/retrieve")
def retrieve(req: RetrieveRequest, x_internal_token: str | None = Header(default=None)):
    """向量召回：供全局搜索、资料推荐等非生成场景使用。"""
    check_internal_token(x_internal_token)
    from app import retriever
    try:
        hits = retriever.search(req.query, kb_id=req.kbId, kb_ids=req.kbIds, top_k=req.topK)
    except Exception as e:  # noqa: BLE001
        logger.error("retrieve 失败: %s", e)
        raise HTTPException(502, f"检索失败: {e}") from e
    return {"hits": hits}


@app.post("/ai/index")
def index(req: IndexRequest, x_internal_token: str | None = Header(default=None)):
    """建索引：从 Java 内部 API 拉取 chunk 向量化写入 Chroma。"""
    check_internal_token(x_internal_token)
    from app import retriever
    try:
        count = retriever.rebuild_all() if req.all else retriever.index_document(req.kbId, req.docId)
    except Exception as e:  # noqa: BLE001
        logger.error("index 失败: %s", e)
        raise HTTPException(502, f"索引失败: {e}") from e
    return {"indexed": count}


@app.post("/ai/index/delete")
def delete_index(req: DeleteIndexRequest, x_internal_token: str | None = Header(default=None)):
    check_internal_token(x_internal_token)
    from app import retriever
    if req.docId is not None:
        retriever.remove_document(req.docId)
    if req.chunkIds:
        retriever.remove_chunks(req.chunkIds)
    return {"ok": True}


@app.get("/ai/rag/stats")
def rag_stats(x_internal_token: str | None = Header(default=None)):
    check_internal_token(x_internal_token)
    from app import retriever
    return retriever.stats()


# ===== 文档解析与分块端点（自 Java 侧 DocumentParser 迁移，仅供 Java 骨架内部调用）=====

@app.post("/ai/parse")
async def parse_document(file: UploadFile = File(...),
                         x_internal_token: str | None = Header(default=None)):
    """解析上传的原始文件字节并切成 chunks，Java 拿到后落 t_chunk。"""
    check_internal_token(x_internal_token)
    from app import parsing

    data = await file.read()
    try:
        text = parsing.parse(file.filename, file.content_type, data)
        chunks = parsing.to_chunks(text)
    except parsing.ParseError as e:
        raise HTTPException(422, str(e)) from e
    return {"chunks": chunks, "textChars": len(text), "fileName": file.filename}


@app.post("/ai/chunk")
def chunk_text(req: ChunkRequest, x_internal_token: str | None = Header(default=None)):
    """纯文本切块（前端粘贴文本、AI 生成讲义、种子数据等非文件场景）。"""
    check_internal_token(x_internal_token)
    from app import parsing

    text = (req.content or "").strip()
    if not text:
        raise HTTPException(422, "文档内容不能为空")
    return {"chunks": parsing.to_chunks(text)}


@app.get("/ai/history/{session_id}")
def history(session_id: str):
    from app import memory
    return {"messages": memory.recent(session_id, rounds=50)}


@app.post("/ai/memory/clear")
def memory_clear(req: MemoryClearRequest, x_internal_token: str | None = Header(default=None)):
    """清空某会话的记忆（JSONL 历史 + 滚动摘要）；Java 删除会话时联动调用，避免孤儿文件。"""
    check_internal_token(x_internal_token)
    from app import memory
    memory.clear(req.sessionId)
    return {"ok": True}


@app.get("/ai/stats")
def ai_stats(hours: int = 24):
    """LLM 调用观测：最近 N 小时的调用量/成功率/分场景耗时。"""
    from app import stats
    return stats.aggregate(hours)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host=config.HOST, port=config.PORT)