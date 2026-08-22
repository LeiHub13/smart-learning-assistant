"""AI 服务入口：FastAPI + SSE 流式接口，供 Java 骨架调用。

接口约定（与 Java 端 PythonAIChatModel 对齐）：
- GET  /ai/health       健康检查，返回模型配置摘要
- POST /ai/complete     非流式生成 {scene, question, chunks?, sessionId?} -> {content}
- POST /ai/stream       流式生成（SSE）{scene, question, chunks?, sessionId?}
                        -> data: {"delta": "..."} ... data: {"done": true}
"""
import json
import logging

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from sse_starlette.sse import EventSourceResponse

from app import chains, config

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
logger = logging.getLogger("ai-service")

app = FastAPI(title="AI Service (langchain)", version="1.0.0")

SCENES = {"rag_qa", "free", "agent", "lecture", "questions", "review", "advice",
          "rewrite", "rerank", "plan", "report"}


class CompleteRequest(BaseModel):
    scene: str
    question: str
    chunks: list | None = Field(default=None)
    sessionId: str | None = Field(default=None)
    userId: int | None = Field(default=None)


class StreamRequest(CompleteRequest):
    pass


@app.get("/ai/health")
def health():
    return {
        "status": "ok",
        "provider": config.PROVIDER,
        "model": config.MODEL,
        "baseUrl": config.BASE_URL,
        "agentScene": True,
    }


@app.post("/ai/complete")
def complete(req: CompleteRequest):
    if req.scene not in SCENES:
        raise HTTPException(400, f"未知场景: {req.scene}")
    try:
        if req.scene == "agent":
            from app import agent
            content = agent.complete_agent(chains.get_model(), req.question, req.chunks, req.sessionId, req.userId)
        else:
            content = chains.complete(req.scene, req.question, req.chunks, req.sessionId)
        return {"content": content}
    except RuntimeError as e:
        logger.error("complete 失败: %s", e)
        raise HTTPException(502, str(e)) from e


@app.post("/ai/stream")
async def stream(req: StreamRequest):
    if req.scene not in SCENES:
        raise HTTPException(400, f"未知场景: {req.scene}")

    def gen():
        try:
            if req.scene == "agent":
                from app import agent
                deltas = agent.stream_agent(chains.get_model(), req.question, req.chunks, req.sessionId, req.userId)
            else:
                deltas = chains.stream(req.scene, req.question, req.chunks, req.sessionId)
            for delta in deltas:
                yield {"event": "message", "data": json.dumps({"delta": delta}, ensure_ascii=False)}
            yield {"event": "message", "data": json.dumps({"done": True})}
        except RuntimeError as e:
            logger.error("stream 失败: %s", e)
            yield {"event": "error", "data": json.dumps({"error": str(e)})}

    return EventSourceResponse(gen(), ping=None)


@app.get("/ai/history/{session_id}")
def history(session_id: str):
    from app import memory
    return {"messages": memory.recent(session_id, rounds=50)}


@app.get("/ai/stats")
def ai_stats(hours: int = 24):
    """LLM 调用观测：最近 N 小时的调用量/成功率/分场景耗时。"""
    from app import stats
    return stats.aggregate(hours)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host=config.HOST, port=config.PORT)