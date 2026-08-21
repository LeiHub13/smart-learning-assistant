"""ReAct Agent：基于 langchain create_agent 的工具调用循环。

与 chains.py 的单次调用不同，这里把「回答学生问题」升级为 Agent 任务：
模型自主决定是否检索资料、检索什么关键词，循环「思考 -> 调工具 -> 观察」
直到信息足够再作答（ReAct 模式）。

工具（Tools）：
- list_material_topics  列出本次请求携带的课程资料清单
- search_materials      按关键词在资料中检索（Java 端向量检索 Top-K 结果）

说明：chunks 由 Java 端检索后随请求传入，故工具用闭包绑定本次请求数据，
每次请求构建一套工具实例（图编译开销远小于一次 LLM 调用，MVP 阶段可接受；
生产化可改用 langgraph checkpointer + context_schema 复用图实例）。
"""
from langchain.agents import create_agent
from langchain_core.messages import AIMessage, AIMessageChunk, HumanMessage, SystemMessage
from langchain_core.tools import tool

from app import memory

SYSTEM_PROMPT = (
    "你是智能学习助手的答疑 Agent。收到学生问题后先思考："
    "如果需要课程资料，先调用 list_material_topics 查看可用资料清单，"
    "再用 search_materials 按关键词检索，最后结合检索结果回答，引用资料时标注编号[n]。"
    "资料中没有的内容要如实说明，不要编造。回答保持简洁、准确。"
)

# 循环上限：防止模型在「检索不到 -> 再检索」里打转
RECURSION_LIMIT = 20


def _make_tools(chunks):
    """构建绑定本次请求资料的工具集（闭包捕获 chunks）。"""
    docs = [str(c).strip() for c in (chunks or []) if str(c).strip()]

    @tool
    def list_material_topics() -> str:
        """列出当前可用的课程知识库资料清单（编号 + 内容摘要）。"""
        if not docs:
            return "当前没有可用的课程资料，请基于自身知识回答并说明未检索到课程资料。"
        lines = []
        for i, d in enumerate(docs, start=1):
            summary = d[:60] + ("…" if len(d) > 60 else "")
            lines.append(f"[{i}] {summary}")
        return "\n".join(lines)

    @tool
    def search_materials(keyword: str) -> str:
        """按关键词在课程知识库资料中检索，返回命中的资料原文（带编号）。"""
        if not docs:
            return "当前没有可用的课程资料。"
        kw = (keyword or "").strip()
        if not kw:
            return "关键词为空，请提供要检索的关键词。"
        hits = [(i, d) for i, d in enumerate(docs, start=1) if kw in d]
        if not hits:
            return f"没有检索到包含“{kw}”的资料，建议先调用 list_material_topics 查看资料清单后换个关键词。"
        return "\n".join(f"[{i}] {d}" for i, d in hits)

    return [list_material_topics, search_materials]


def _build_messages(question: str, session_id: str = None) -> list:
    """系统提示 + 会话记忆 + 当前问题。"""
    msgs = [SystemMessage(content=SYSTEM_PROMPT)]
    if session_id:
        for row in memory.recent(session_id):
            role = row.get("role")
            if role == "user":
                msgs.append(HumanMessage(content=row["content"]))
            elif role == "assistant":
                msgs.append(AIMessage(content=row["content"]))
    msgs.append(HumanMessage(content=question))
    return msgs


def _remember(session_id: str, question: str, answer: str) -> None:
    if session_id:
        memory.add(session_id, "user", question)
        memory.add(session_id, "assistant", answer)


def complete_agent(model, question: str, chunks=None, session_id: str = None) -> str:
    """非流式：跑完整 ReAct 循环后返回最终回答。"""
    agent = create_agent(model, _make_tools(chunks), system_prompt=SYSTEM_PROMPT)
    result = agent.invoke(
        {"messages": _build_messages(question, session_id)},
        config={"recursion_limit": RECURSION_LIMIT},
    )
    answer = result["messages"][-1].content
    _remember(session_id, question, answer)
    return answer


def stream_agent(model, question: str, chunks=None, session_id: str = None):
    """流式：逐块 yield 最终回答的 token。

    stream_mode="messages" 会产出 (message_chunk, metadata) 元组，
    只透传 AIMessageChunk 的文本内容；工具调用块 content 为空，自然被过滤。
    """
    agent = create_agent(model, _make_tools(chunks), system_prompt=SYSTEM_PROMPT)
    full = ""
    try:
        for item in agent.stream(
            {"messages": _build_messages(question, session_id)},
            stream_mode="messages",
            config={"recursion_limit": RECURSION_LIMIT},
        ):
            chunk = item[0] if isinstance(item, tuple) else item
            if isinstance(chunk, AIMessageChunk) and chunk.content:
                full += chunk.content
                yield chunk.content
    except Exception as e:  # noqa: BLE001
        raise RuntimeError(f"Agent 调用失败: {e}") from e
    _remember(session_id, question, full)
