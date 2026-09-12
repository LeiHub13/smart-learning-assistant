"""ReAct Agent：基于 langchain create_agent 的工具调用循环。

工具（Tools）：
- list_material_topics    列出本次请求携带的课程资料清单
- search_materials        按关键词在资料中检索
- query_wrong_book        查当前用户的错题本（回调 Java 内部 API）
- query_mastery           查当前用户各知识点掌握度明细（回调 Java）
- query_recent_practices  查当前用户最近练习记录（回调 Java）
- query_kb_documents      查课程知识库文档清单（回调 Java）
- MCP 外部工具            通过 AI_MCP_CONFIG 接入（如联网搜索 tavily-mcp），
                          与内置工具平权合并进 Agent，加载失败自动降级为仅内置工具

chunks/userId 由 Java 端随请求传入，工具用闭包绑定本次请求上下文。
Java 回调走内部 API（/internal/tools/**，带内部 token，不暴露给前端）。
"""
import asyncio
import json
import logging
import threading
import time
import urllib.request

from langchain.agents import create_agent
from langchain_core.messages import AIMessage, AIMessageChunk, HumanMessage, SystemMessage
from langchain_core.tools import tool

from app import config, memory

logger = logging.getLogger("ai-service.agent")

SYSTEM_PROMPT = (
    "你是智能学习助手的答疑 Agent。收到学生问题后先思考需要哪些信息，再决定调用工具：\n"
    "- 需要课程资料：先 list_material_topics 查看资料清单，再 search_materials 按关键词检索；\n"
    "- 需要了解学生的学习情况：query_mastery（掌握度）、query_wrong_book（错题）、"
    "query_recent_practices（最近练习）、query_kb_documents（知识库文档清单）。\n"
    "最后结合收集到的信息回答，引用资料时标注编号[n]。查不到的内容如实说明，不要编造。"
    "回答保持简洁、准确、有针对性。"
)

# MCP 外部工具接入后追加的提示（仅在有 MCP 工具时拼接）
MCP_PROMPT_SUFFIX = (
    "\n此外你还接入了外部 MCP 工具（如联网搜索）。当问题涉及最新资讯、实时数据、"
    "或你不确定的事实性内容时，优先调用相应外部工具查证后再回答，并注明信息来源；"
    "工具不可用时如实说明，不要编造。"
)

RECURSION_LIMIT = 20

# ===== MCP 工具懒加载（全局缓存 + 失败冷却） =====
_MCP_LOCK = threading.Lock()
_MCP_TOOLS = None       # None=尚未加载；[]=[]=加载失败/未配置
_MCP_RETRY_AT = 0.0     # 失败后多久内不再重试（unix 时间戳）


def _normalize_servers(raw: dict) -> dict:
    """补齐 transport 缺省值（与 Claude Desktop 的 mcpServers 格式兼容）。"""
    servers = {}
    for name, cfg in (raw or {}).items():
        c = dict(cfg or {})
        c.setdefault("transport", "stdio")
        servers[name] = c
    return servers


def _run_async(coro):
    """在同步线程里执行协程；若当前线程已有事件循环（不该发生），退化为独立线程执行。"""
    try:
        asyncio.get_running_loop()
    except RuntimeError:
        return asyncio.run(coro)
    import concurrent.futures
    with concurrent.futures.ThreadPoolExecutor(max_workers=1) as ex:
        return ex.submit(asyncio.run, coro).result()


def _wrap_sync(t):
    """adapter 返回的 MCP 工具是 async-only（StructuredTool 不支持同步调用），
    而本项目 Agent 走同步 invoke/stream 链路——包一层同步入口，协程在调用线程新开事件循环执行。"""
    from langchain_core.tools import StructuredTool

    async def _acall(**kwargs):
        return await t.ainvoke(kwargs)

    def _scall(**kwargs):
        return _run_async(_acall(**kwargs))

    return StructuredTool(
        name=t.name,
        description=t.description or "",
        args_schema=t.args_schema,
        func=_scall,
        coroutine=_acall,
    )


async def _load_mcp_tools_async() -> list:
    from langchain_mcp_adapters.client import MultiServerMCPClient

    servers = _normalize_servers(config.MCP_SERVERS)
    if not servers:
        return []
    client = MultiServerMCPClient(servers)
    return [_wrap_sync(t) for t in await client.get_tools()]


async def init_mcp_tools() -> None:
    """服务启动时预热 MCP 工具（FastAPI lifespan 调用）；失败只记日志不阻塞启动。"""
    global _MCP_TOOLS, _MCP_RETRY_AT
    if not config.MCP_SERVERS:
        return
    try:
        tools = await _load_mcp_tools_async()
        _MCP_TOOLS = tools
        _MCP_RETRY_AT = 0.0
        logger.info("MCP 工具加载成功: servers=%s tools=%s",
                    list(config.MCP_SERVERS.keys()), [t.name for t in tools])
    except Exception as e:  # noqa: BLE001
        _MCP_TOOLS = []
        _MCP_RETRY_AT = time.time() + config.MCP_RETRY_COOLDOWN
        logger.warning("MCP 工具加载失败（%s 秒后重试）: %s", config.MCP_RETRY_COOLDOWN, e)


def get_mcp_tools() -> list:
    """获取 MCP 工具：成功结果缓存复用；失败进入冷却期直接返回 []，冷却到期才重试。"""
    global _MCP_TOOLS, _MCP_RETRY_AT
    if not config.MCP_SERVERS:
        return []

    def _retry_due(snapshot):
        # 仅"失败缓存([]) 且冷却已到期"时需要重新加载
        return snapshot == [] and time.time() >= _MCP_RETRY_AT

    cached = _MCP_TOOLS
    if cached is not None and not _retry_due(cached):
        return cached  # 快速路径：成功缓存 或 冷却期内
    with _MCP_LOCK:
        cached = _MCP_TOOLS
        if cached is not None and not _retry_due(cached):
            return cached
        try:
            # 本函数可能在事件循环线程被调（如同步端点误用），此时不能 asyncio.run
            try:
                asyncio.get_running_loop()
            except RuntimeError:
                _MCP_TOOLS = asyncio.run(_load_mcp_tools_async())
                _MCP_RETRY_AT = 0.0
                logger.info("MCP 工具懒加载成功: %s", [t.name for t in _MCP_TOOLS])
            else:
                logger.warning("MCP 工具加载被跳过：当前线程存在运行中的事件循环")
                return _MCP_TOOLS or []
        except Exception as e:  # noqa: BLE001
            _MCP_TOOLS = []
            _MCP_RETRY_AT = time.time() + config.MCP_RETRY_COOLDOWN
            logger.warning("MCP 工具懒加载失败（%s 秒后重试）: %s", config.MCP_RETRY_COOLDOWN, e)
    return _MCP_TOOLS


def mcp_status() -> dict:
    """供 /ai/health 展示的 MCP 接入状态。"""
    return {
        "enabled": bool(config.MCP_SERVERS),
        "servers": list(config.MCP_SERVERS.keys()),
        "tools": [t.name for t in (_MCP_TOOLS or [])],
    }


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
    mcp_tools = get_mcp_tools()
    tools = _make_tools(chunks, user_id) + mcp_tools
    prompt = SYSTEM_PROMPT + (MCP_PROMPT_SUFFIX if mcp_tools else "")
    agent = create_agent(model, tools, system_prompt=prompt)
    result = agent.invoke(
        {"messages": _build_messages(question, session_id)},
        config={"recursion_limit": RECURSION_LIMIT},
    )
    answer = result["messages"][-1].content
    _remember(session_id, question, answer)
    return answer


def stream_agent(model, question: str, chunks=None, session_id: str = None, user_id=None):
    """流式：逐块 yield 最终回答的 token。"""
    mcp_tools = get_mcp_tools()
    tools = _make_tools(chunks, user_id) + mcp_tools
    prompt = SYSTEM_PROMPT + (MCP_PROMPT_SUFFIX if mcp_tools else "")
    agent = create_agent(model, tools, system_prompt=prompt)
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
