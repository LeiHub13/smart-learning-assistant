"""RAG 流水线：查询改写 -> Chroma 向量召回 -> LLM 重排 -> 拼装带 [n] 编号的资料上下文。

原实现在 Java 侧 ChatService（buildContext / rewriteQuery / rerankChunks），
迁移到本模块后由 ai-service 自行完成检索与上下文组装，并把引用编号 sources 回传给 Java 落库。

sources 格式与原实现保持一致：逗号分隔的引用编号（"1,2,3"），前端按编号展示「引用来源」。
"""
import json
import logging

from app import config, retriever

logger = logging.getLogger("ai-service.rag")


class RagContext:
    def __init__(self, context: str, sources: str, hits: list[dict]):
        self.context = context
        self.sources = sources
        self.hits = hits

    @property
    def empty(self) -> bool:
        return not self.hits


def build_context(kb_id, question: str, history: list[dict] | None = None,
                  kb_ids: list[int] | None = None) -> RagContext:
    """检索并组装知识库上下文。无命中时返回「暂无相关资料」，与原 Java 行为一致。

    kb_ids 为课程级检索范围（本课程全部知识库 id），缺省只搜 kb_id 单库。
    """
    query = question
    if config.RAG_REWRITE_ENABLED and history:
        query = _rewrite(question, history) or question

    candidate_k = config.RAG_RERANK_CANDIDATES if config.RAG_RERANK_ENABLED else config.RAG_TOP_K
    try:
        hits = retriever.search(query, kb_id=kb_id, kb_ids=kb_ids,
                                top_k=config.RAG_TOP_K, candidate_k=candidate_k)
    except Exception as e:  # noqa: BLE001
        logger.error("向量召回失败 kbId=%s kbIds=%s: %s", kb_id, kb_ids, e)
        return RagContext("暂无相关资料", "", [])

    if not hits:
        return RagContext("暂无相关资料", "", [])

    if config.RAG_RERANK_ENABLED and len(hits) > config.RAG_TOP_K:
        hits = _rerank(query, hits)

    picked = hits[:config.RAG_TOP_K]
    lines = [f"[{i}] {h['content']}" for i, h in enumerate(picked, start=1)]
    sources = ",".join(str(i) for i in range(1, len(picked) + 1))
    return RagContext("\n".join(lines), sources, picked)


def _rewrite(question: str, history: list[dict]) -> str | None:
    """指代消解：把多轮里的最新问题改写成独立完整的检索查询。失败返回 None 用原问题。"""
    from langchain_core.messages import HumanMessage, SystemMessage

    from app import chains
    try:
        transcript = "\n".join(
            ("user" if m.get("role") == "user" else "assistant") + ":" + str(m.get("content", ""))
            for m in history
        ) + "\nuser:" + question
        raw = chains.complete_with(
            [SystemMessage("QUERY_REWRITE\n把用户最新问题改写成独立完整的检索查询。只输出改写后的查询本身。"),
             HumanMessage(transcript)],
            scene="rewrite",
        )
        text = (raw or "").strip()
        return text or None
    except Exception as e:  # noqa: BLE001
        logger.warning("查询改写失败，使用原问题: %s", e)
        return None


def _rerank(question: str, hits: list[dict]) -> list[dict]:
    """LLM 重排：按相关性返回候选编号顺序。失败保持向量召回原排序。"""
    from langchain_core.messages import HumanMessage, SystemMessage

    from app import chains
    try:
        prompt = "问题：" + question + "\n\n" + "\n".join(
            f"[{i}] {h['content']}" for i, h in enumerate(hits, start=1)
        )
        raw = chains.complete_with(
            [SystemMessage("RERANK\n你是相关性排序器。按与问题的相关程度从高到低排序，"
                           "只输出 JSON 数组（元素为片段编号整数）。"),
             HumanMessage(prompt)],
            scene="rerank",
        )
        order = json.loads(chains._ensure_json_array(raw))
        reordered = [hits[n - 1] for n in order if isinstance(n, int) and 1 <= n <= len(hits)]
        return reordered or hits
    except Exception as e:  # noqa: BLE001
        logger.warning("Rerank 失败，使用向量原排序: %s", e)
        return hits
