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

# 开启写动作工具后追加的提示：先查再改；三个写工具都只登记待确认动作，人点确认后才生效
WRITE_PROMPT_SUFFIX = (
    "\n你还可以为学生登记三类动作：schedule_review 安排复习提醒、"
    "finish_plan_task 给学习任务打卡、save_material_to_kb 把答疑中值得留存的一段资料存进本课程知识库。"
    "注意：这些写工具都不会直接写入，它们只生成「待确认动作」，必须由用户在消息下方点击『确认执行』后才真正生效；"
    "因此你绝不能声称提醒已安排、打卡已完成或资料已保存，调用后应提醒用户在消息下方点击『确认执行』。"
    "登记前先确认信息（打卡前必须先用 query_plan_tasks 拿到 taskId），"
    "一次对话里不要重复登记同一条动作，也不要在学生没提出意图时擅自登记。"
)

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


def _post_java_tool(path: str, payload: dict) -> str:
    """回调 Java 内部写接口。失败时返回友好说明，不中断 Agent 循环。"""
    url = config.JAVA_TOOL_BASE.rstrip("/") + path
    req = urllib.request.Request(
        url, data=json.dumps(payload, ensure_ascii=False).encode("utf-8"), method="POST",
        headers={"X-Internal-Token": config.JAVA_TOOL_TOKEN, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            body = json.loads(resp.read().decode("utf-8"))
            if body.get("code") != 0:
                return "操作失败：" + str(body.get("message", "未知错误"))
            return json.dumps(body.get("data") or {}, ensure_ascii=False)
    except Exception as e:  # noqa: BLE001
        return f"操作未能完成（{type(e).__name__}），请如实告知用户，不要重复尝试。"


def _make_tools(chunks, user_id, kb_id=None, course_id=None, kb_ids=None, session_id=None):
    """构建绑定本次请求上下文的工具集（闭包捕获 chunks / user_id / kb_id / kb_ids / course_id / session_id）。

    带 kb_id 时 search_materials 走真正的向量检索器（检索范围为会话绑定的知识库，
    或按 kb_ids 扩展为该课程的全部知识库）；
    否则退回 Java 随请求下发的 chunks 集合做关键词匹配。
    course_id/session_id 由会话上下文绑定而非模型填写，避免跨课程取错数据。
    """
    docs = [str(c).strip() for c in (chunks or []) if str(c).strip()]

    @tool
    def list_material_topics() -> str:
        """列出当前可用的课程知识库资料清单（编号 + 内容摘要）。"""
        if not docs:
            if kb_id:
                return f"已接入知识库（kbId={kb_id}），可直接用 search_materials 按关键词检索该知识库（或本课程全部知识库）内的资料。"
            return "当前没有可用的课程资料，请基于自身知识回答并说明未检索到课程资料。"
        lines = []
        for i, d in enumerate(docs, start=1):
            summary = d[:60] + ("..." if len(d) > 60 else "")
            lines.append(f"[{i}] {summary}")
        return "\n".join(lines)

    @tool
    def search_materials(keyword: str) -> str:
        """按关键词在课程知识库资料中检索，返回命中的资料原文（带编号）。"""
        kw = (keyword or "").strip()
        if not kw:
            return "关键词为空，请提供要检索的关键词。"
        if kb_id:
            try:
                from app import retriever
                hits = retriever.search(kw, kb_id=kb_id, kb_ids=kb_ids, top_k=5)
                if hits:
                    return "\n".join(f"[{i}] {h['content']}" for i, h in enumerate(hits, start=1))
            except Exception as e:  # noqa: BLE001
                logger.warning("search_materials 向量检索失败，回退关键词匹配: %s", e)
        if not docs:
            return "当前没有可用的课程资料。"
        local = [(i, d) for i, d in enumerate(docs, start=1) if kw in d]
        if not local:
            return f"没有检索到包含该关键词的资料：{kw}，建议先调用 list_material_topics 查看清单后换关键词。"
        return "\n".join(f"[{i}] {d}" for i, d in local)

    @tool
    def query_wrong_book() -> str:
        """查询当前学生的错题本（题目、知识点、错答、正确答案、出错时间）。"""
        if not user_id:
            return "未提供用户信息，无法查询错题本。"
        return _call_java_tool(f"/internal/tools/wrong-book?userId={user_id}")

    @tool
    def query_mastery() -> str:
        """查询当前学生在当前课程下各知识点的掌握度明细（百分比、练习次数、正确率）。"""
        if not user_id:
            return "未提供用户信息，无法查询掌握度。"
        if not course_id:
            return "当前会话未绑定课程，无法查询掌握度。"
        return _call_java_tool(f"/internal/tools/mastery?userId={user_id}&courseId={course_id}")

    @tool
    def query_recent_practices() -> str:
        """查询当前学生最近的练习记录（标题、得分、满分、时间）。"""
        if not user_id:
            return "未提供用户信息，无法查询练习记录。"
        return _call_java_tool(f"/internal/tools/recent-practices?userId={user_id}")

    @tool
    def query_kb_documents() -> str:
        """查询当前课程的知识库与文档清单（知识库名、文档名、分块数）。"""
        if not course_id:
            return "当前会话未绑定课程，无法查询知识库清单。"
        return _call_java_tool(f"/internal/tools/kb-documents?courseId={course_id}")

    read_tools = [list_material_topics, search_materials,
                  query_wrong_book, query_mastery, query_recent_practices, query_kb_documents]

    @tool
    def query_plan_tasks(only_pending: bool = True) -> str:
        """查询当前学生的学习计划任务清单（含 taskId、日期、标题、是否已打卡）。打卡前先调用它拿到 taskId。"""
        if not user_id:
            return "未提供用户信息，无法查询学习任务。"
        flag = "true" if only_pending else "false"
        return _call_java_tool(f"/internal/tools/plan-tasks?userId={user_id}&onlyPending={flag}")

    def _propose_action(kind: str, inner: dict) -> str:
        """登记待确认动作的统一出口：只生成 proposal，真正写入要等用户点『确认执行』。

        userId/courseId/sessionId 一律由闭包（即会话上下文）绑定，模型无从伪造。
        """
        payload = {"userId": user_id, "courseId": course_id, "kind": kind, "payload": inner}
        if session_id:
            try:
                payload["sessionId"] = int(session_id)
            except (TypeError, ValueError):
                payload["sessionId"] = session_id
        result = _post_java_tool("/internal/tools/actions/propose", payload)
        if result.startswith("操作失败") or result.startswith("操作未能完成"):
            return result
        try:
            summary = (json.loads(result) or {}).get("summary") or ""
        except (ValueError, TypeError):
            summary = ""
        return (f"已生成待确认动作：{summary}。这不会自动写入，"
                "请告诉用户在消息下方点击『确认执行』才会生效。")

    @tool
    def schedule_review(kp_name: str, remind_at: str = "", note: str = "") -> str:
        """为学生安排一条复习提醒（登记为待确认动作，用户确认后才真正生效）。
        remind_at 传 yyyy-MM-dd（当天 09:00）或 yyyy-MM-ddTHH:mm，留空表示立即提醒；
        note 是一句话备注（会被截断），提醒正文由系统模板生成。"""
        if not user_id:
            return "未提供用户信息，无法安排复习提醒。"
        if not (kp_name or "").strip():
            return "请提供要复习的知识点名称。"
        return _propose_action("schedule_review", {
            "kpName": kp_name.strip(), "remindAt": (remind_at or "").strip(),
            "note": (note or "").strip()})

    @tool
    def finish_plan_task(task_id: int) -> str:
        """给指定学习任务打卡（登记为待确认动作，用户确认后才真正生效）。
        必须先用 query_plan_tasks 拿到 taskId；已打卡的任务会被服务端拒绝登记。"""
        if not user_id:
            return "未提供用户信息，无法打卡。"
        return _propose_action("finish_plan_task", {"taskId": int(task_id)})

    @tool
    def save_material_to_kb(title: str, content: str) -> str:
        """把答疑中值得留存的一段资料登记为「待确认动作」，由用户确认后才存入当前课程的知识库。
        该工具不会直接写入任何数据；kbId/courseId 由会话上下文绑定，不由模型填写。"""
        if not user_id:
            return "未提供用户信息，无法登记资料。"
        if not (title or "").strip() or not (content or "").strip():
            return "请提供资料标题与正文内容。"
        inner = {"title": title.strip(), "content": content.strip()}
        if kb_id:
            inner["kbId"] = kb_id
        return _propose_action("add_material", inner)

    return read_tools + ([query_plan_tasks, schedule_review, finish_plan_task, save_material_to_kb]
                         if config.AGENT_WRITE_TOOLS else [])


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


def _seed_materials(chunks, session_id, kb_id, question, meta, kb_ids=None):
    """Agent 模式下若未携带资料，先用 RAG 流水线召回一轮，产出 sources 引用编号。"""
    if chunks or not kb_id:
        meta.setdefault("sources", "")
        return chunks
    from app import rag
    ctx = rag.build_context(kb_id, question, memory.recent(session_id) if session_id else None,
                            kb_ids=kb_ids)
    if meta is not None:
        meta["sources"] = ctx.sources
    return [h["content"] for h in ctx.hits]


def _system_prompt(mcp_tools: list, note: str = None) -> str:
    from app import chains
    return (SYSTEM_PROMPT
            + (MCP_PROMPT_SUFFIX if mcp_tools else "")
            + (WRITE_PROMPT_SUFFIX if config.AGENT_WRITE_TOOLS else "")
            + chains._note_suffix(note))


def complete_agent(model, question: str, chunks=None, session_id: str = None,
                   user_id=None, kb_id=None, kb_ids=None, note: str = None, meta: dict | None = None,
                   course_id=None) -> str:
    """非流式：跑完整 ReAct 循环后返回最终回答。"""
    meta = meta if meta is not None else {}
    chunks = _seed_materials(chunks, session_id, kb_id, question, meta, kb_ids=kb_ids)
    mcp_tools = get_mcp_tools()
    tools = _make_tools(chunks, user_id, kb_id, course_id, kb_ids=kb_ids,
                        session_id=session_id) + mcp_tools
    agent = create_agent(model, tools, system_prompt=_system_prompt(mcp_tools, note))
    result = agent.invoke(
        {"messages": _build_messages(question, session_id)},
        config={"recursion_limit": RECURSION_LIMIT},
    )
    answer = result["messages"][-1].content
    _remember(session_id, question, answer)
    return answer


def stream_agent(model, question: str, chunks=None, session_id: str = None,
                 user_id=None, kb_id=None, kb_ids=None, note: str = None, meta: dict | None = None,
                 course_id=None):
    """流式：逐块 yield 最终回答的 token。"""
    meta = meta if meta is not None else {}
    chunks = _seed_materials(chunks, session_id, kb_id, question, meta, kb_ids=kb_ids)
    mcp_tools = get_mcp_tools()
    tools = _make_tools(chunks, user_id, kb_id, course_id, kb_ids=kb_ids,
                        session_id=session_id) + mcp_tools
    agent = create_agent(model, tools, system_prompt=_system_prompt(mcp_tools, note))
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
