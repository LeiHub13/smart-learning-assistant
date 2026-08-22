"""ReAct Agent：基于 langchain create_agent 的工具调用循环。

工具（Tools）：
- list_material_topics    列出本次请求携带的课程资料清单
- search_materials        按关键词在资料中检索
- query_wrong_book        查当前用户的错题本（回调 Java 内部 API）
- query_mastery           查当前用户各知识点掌握度明细（回调 Java）
- query_recent_practices  查当前用户最近练习记录（回调 Java）
- query_kb_documents      查课程知识库文档清单（回调 Java）

chunks/userId 由 Java 端随请求传入，工具用闭包绑定本次请求上下文。
Java 回调走内部 API（/internal/tools/**，带内部 token，不暴露给前端）。
"""
import json
import urllib.request

from langchain.agents import create_agent
from langchain_core.messages import AIMessage, AIMessageChunk, HumanMessage, SystemMessage
from langchain_core.tools import tool

from app import config, memory

SYSTEM_PROMPT = (
    "你是智能学习助手的答疑 Agent。收到学生问题后先思考需要哪些信息，再决定调用工具：\n"
    "- 需要课程资料：先 list_material_topics 查看资料清单，再 search_materials 按关键词检索；\n"
    "- 需要了解学生的学习情况：query_mastery（掌握度）、query_wrong_book（错题）、"
    "query_recent_practices（最近练习）、query_kb_documents（知识库文档清单）。\n"
    "最后结合收集到的信息回答，引用资料时标注编号[n]。查不到的内容如实说明，不要编造。"
    "回答保持简洁、准确、有针对性。"
)

RECURSION_LIMIT = 20


def _call_java_tool(path: str) -> str:
    """回调 Java 内部工具 API。失败时返回友好说明，不中断 Agent 循环。"""
    url = config.JAVA_TOOL_BASE.rstrip("/") + path
    req = urllib.request.Request(url, headers={"X-Internal-Token": config.JAVA_TOOL_TOKEN})
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            body = json.loads(resp.read().decode("utf-8"))
            if body.get("code") != 0:
                return "查询失败：" + str(body.get("message", "未知错误"))
            data = body.get("data")
            return json.dumps(data, ensure_ascii=False) if data else "暂无数据"
    except Exception as e:  # noqa: BLE001
        return f"暂时无法获取该数据（{type(e).__name__}），请基于已知信息回答。"


def _make_tools(chunks, user_id):
    """构建绑定本次请求上下文的工具集（闭包捕获 chunks / user_id）。"""
    docs = [str(c).strip() for c in (chunks or []) if str(c).strip()]

    @tool
    def list_material_topics() -> str:
        """列出当前可用的课程知识库资料清单（编号 + 内容摘要）。"""
        if not docs:
            return "当前没有可用的课程资料，请基于自身知识回答并说明未检索到课程资料。"
        lines = []
        for i, d in enumerate(docs, start=1):
            summary = d[:60] + ("..." if len(d) > 60 else "")
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
            return f"没有检索到包含该关键词的资料：{kw}，建议先调用 list_material_topics 查看清单后换关键词。"
        return "\n".join(f"[{i}] {d}" for i, d in hits)

    @tool
    def query_wrong_book() -> str:
        """查询当前学生的错题本（题目、知识点、错答、正确答案、出错时间）。"""
        if not user_id:
            return "未提供用户信息，无法查询错题本。"
        return _call_java_tool(f"/internal/tools/wrong-book?userId={user_id}")

    @tool
    def query_mastery(course_id: int = 1) -> str:
        """查询当前学生在指定课程下各知识点的掌握度明细（百分比、练习次数、正确率）。"""
        if not user_id:
            return "未提供用户信息，无法查询掌握度。"
        return _call_java_tool(f"/internal/tools/mastery?userId={user_id}&courseId={course_id}")

    @tool
    def query_recent_practices() -> str:
        """查询当前学生最近的练习记录（标题、得分、满分、时间）。"""
        if not user_id:
            return "未提供用户信息，无法查询练习记录。"
        return _call_java_tool(f"/internal/tools/recent-practices?userId={user_id}")

    @tool
    def query_kb_documents(course_id: int = 1) -> str:
        """查询指定课程的知识库与文档清单（知识库名、文档名、分块数）。"""
        return _call_java_tool(f"/internal/tools/kb-documents?courseId={course_id}")

    return [list_material_topics, search_materials,
            query_wrong_book, query_mastery, query_recent_practices, query_kb_documents]


def _build_messages(question: str, session_id: str = None) -> list:
    """系统提示 + 会话摘要 + 最近 N 轮 + 当前问题。"""
    msgs = [SystemMessage(content=SYSTEM_PROMPT)]
    if session_id:
        s = memory.summary(session_id)
        if s:
            msgs.append(SystemMessage(content="【此前对话摘要】\n" + s))
        for row in memory.recent(session_id):
            role = row.get("role")
            if role == "user":
                msgs.append(HumanMessage(content=row["content"]))
            elif role == "assistant":
                msgs.append(AIMessage(content=row["content"]))
    msgs.append(HumanMessage(content=question))
    return msgs


def _summarizer(old_summary: str, rows_text: str) -> str:
    from app import chains
    return chains._summarizer(old_summary, rows_text)


def _remember(session_id: str, question: str, answer: str) -> None:
    if session_id:
        memory.add(session_id, "user", question)
        memory.add(session_id, "assistant", answer)
        memory.maybe_compress(session_id, _summarizer)


def complete_agent(model, question: str, chunks=None, session_id: str = None, user_id=None) -> str:
    """非流式：跑完整 ReAct 循环后返回最终回答。"""
    agent = create_agent(model, _make_tools(chunks, user_id), system_prompt=SYSTEM_PROMPT)
    result = agent.invoke(
        {"messages": _build_messages(question, session_id)},
        config={"recursion_limit": RECURSION_LIMIT},
    )
    answer = result["messages"][-1].content
    _remember(session_id, question, answer)
    return answer


def stream_agent(model, question: str, chunks=None, session_id: str = None, user_id=None):
    """流式：逐块 yield 最终回答的 token。"""
    agent = create_agent(model, _make_tools(chunks, user_id), system_prompt=SYSTEM_PROMPT)
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
