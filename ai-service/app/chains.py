"""场景链：基于 langchain 的提示词模板 + 模型调用（流式/非流式）。

场景清单：
- rag_qa / free     答疑（带会话记忆 + 摘要）
- agent             ReAct Agent（自主调工具，见 agent.py）
- lecture           讲义生成
- questions         出题（JSON 数组）
- review            主观题批改（JSON 对象）
- advice            复习建议
- rewrite           检索前查询改写（指代消解 -> 独立查询）
- rerank            候选 chunk 重排序（输入编号列表，输出最相关编号 JSON）
- plan              学习计划生成（JSON 数组：每日任务）
- report            学习报告（Markdown）
"""
import json
import logging
import re
import threading
import time

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage
from langchain_openai import ChatOpenAI

from app import config, memory, stats

logger = logging.getLogger("ai-service.chains")

_MODEL = None

# LLM 并发限制：防止突发流量打爆 token 额度 / 触发上游限流
_SEMAPHORE = threading.Semaphore(int(config.LLM_MAX_CONCURRENCY))
_SEMAPHORE_TIMEOUT = 30  # 排队上限（秒），超时直接报错而不是无限等待


def get_model() -> ChatOpenAI:
    global _MODEL
    if _MODEL is None:
        if not config.API_KEY:
            raise RuntimeError("AI_API_KEY 未配置，请复制 .env.example 为 .env 并填写")
        _MODEL = ChatOpenAI(
            model=config.MODEL,
            api_key=config.API_KEY,
            base_url=config.BASE_URL,
            temperature=0.7,
            max_tokens=2048,
            streaming=True,
            timeout=config.LLM_TIMEOUT,
        )
    return _MODEL


def _chunks_to_text(chunks) -> str:
    if not chunks:
        return "暂无相关资料"
    if isinstance(chunks, str):
        return chunks
    parts = []
    for i, c in enumerate(chunks, start=1):
        content = c.get("content", str(c)) if isinstance(c, dict) else str(c)
        parts.append(f"[{i}] {content}")
    return "\n".join(parts)


def _build_messages(scene: str, question: str, chunks) -> list:
    chunks_text = _chunks_to_text(chunks)
    if scene == "rag_qa":
        return [
            SystemMessage(
                "你是智能学习助手，请结合下方课程知识库资料回答学生问题，引用资料时标注编号[1][2]等；"
                "若资料与问题无关则如实说明。\n【知识库资料】\n" + chunks_text + "\n【资料结束】"
            ),
            HumanMessage(question),
        ]
    if scene == "free":
        return [
            SystemMessage("你是智能学习助手，用中文友好、准确地解答学生的学习问题。"),
            HumanMessage(question),
        ]
    if scene == "lecture":
        return [
            SystemMessage(
                "你是课程教师助手，请为课程生成一份结构清晰的 Markdown 讲义，包含：课程目标、知识要点、示例讲解、练习与思考。"
            ),
            HumanMessage(question),
        ]
    if scene == "questions":
        return [
            SystemMessage(
                "你是出题老师。只输出一个 JSON 数组，不要输出任何其他文字。"
                "每道题字段：type(单选/多选/判断/问答), stem(题目), options(数组[{k,v}]，问答题为 null), "
                "answer(参考答案；多选答案如\"AB\"), analysis(解析), kpName(知识点), difficulty(基础/进阶/综合)。"
            ),
            HumanMessage(question),
        ]
    if scene == "review":
        return [
            SystemMessage(
                "你是批改老师，依据参考答案给学生答案打分（0-10 分整数）并点评。"
                "只输出 JSON：{\"score\":数字,\"comment\":\"点评\"}，不要输出其他文字。"
            ),
            HumanMessage(question),
        ]
    if scene == "advice":
        return [
            SystemMessage(
                "你是学习规划师，根据掌握度数据给出个性化、可执行的复习建议（分条列出）。"
            ),
            HumanMessage(question),
        ]
    if scene == "rewrite":
        return [
            SystemMessage(
                "你是查询改写器。根据对话历史，把用户最新的问题改写成一个独立的、"
                "信息完整的检索查询（补全指代、省略成分）。只输出改写后的查询本身，不要输出任何其他文字。"
                "若原问题已经独立完整，原样输出即可。"
            ),
            HumanMessage(question),
        ]
    if scene == "rerank":
        return [
            SystemMessage(
                "你是相关性排序器。给定用户问题和若干候选资料片段（带编号），"
                "按与问题的相关程度从高到低排序。只输出 JSON 数组，元素为片段编号（整数），"
                "如 [3,1,5]。不要输出其他文字。"
            ),
            HumanMessage(question + "\n\n【候选资料】\n" + chunks_text),
        ]
    if scene == "plan":
        return [
            SystemMessage(
                "你是学习计划制定专家。根据用户的学习目标、可用时间与知识点掌握度数据，"
                "制定逐日的学习计划。只输出 JSON 数组，不要输出其他文字。"
                "每天一个元素，字段：day(第几天，从1开始), date(日期YYYY-MM-DD), title(当日主题), "
                "tasks(数组，具体任务条目), focusKp(重点知识点)。"
            ),
            HumanMessage(question),
        ]
    if scene == "report":
        return [
            SystemMessage(
                "你是学习分析师。根据给定的学习数据（练习记录、掌握度变化、错题分布），"
                "生成一份 Markdown 学习报告，包含：周期概览、数据亮点、薄弱点分析、下周建议。"
                "语气客观、具体、可执行。"
            ),
            HumanMessage(question),
        ]
    raise ValueError(f"未知场景: {scene}")


def _summarizer(old_summary: str, rows_text: str) -> str:
    """滚动摘要回调：把旧摘要 + 待压缩历史压缩为新摘要。"""
    prompt = (
        "请把以下对话历史压缩为简洁的中文摘要（保留关键知识点、用户薄弱点、已达成的结论），"
        "不超过 200 字。只输出摘要本身。\n"
        + (f"【已有摘要】{old_summary}\n" if old_summary else "")
        + f"【对话历史】\n{rows_text}"
    )
    return get_model().invoke([HumanMessage(prompt)]).content.strip()


def _assemble(scene: str, question: str, chunks, session_id: str) -> list:
    """组装消息：系统提示 + （摘要）+ 最近 N 轮 + 当前问题。"""
    msgs = _build_messages(scene, question, chunks)
    if session_id and scene in ("rag_qa", "free"):
        head = [msgs[0]]
        s = memory.summary(session_id)
        if s:
            head.append(SystemMessage("【此前对话摘要】\n" + s))
        return head + memory.recent(session_id) + [msgs[-1]]
    return msgs


def _memorable(scene: str) -> bool:
    return scene in ("rag_qa", "free")


def complete(scene: str, question: str, chunks=None, session_id: str = None) -> str:
    """非流式完整回答（生成/批改/建议），带记忆。"""
    if scene == "agent":
        from app import agent
        return agent.complete_agent(get_model(), question, chunks, session_id)
    msgs = _assemble(scene, question, chunks, session_id)
    answer = _call_with_limit(lambda: get_model().invoke(msgs).content, scene)
    if session_id and _memorable(scene):
        memory.add(session_id, "user", question)
        memory.add(session_id, "assistant", answer)
        memory.maybe_compress(session_id, _summarizer)
    if scene in ("questions", "plan"):
        return _ensure_json_array(answer)
    if scene in ("review", "rerank"):
        return _ensure_json_object(answer) if scene == "review" else _ensure_json_array(answer)
    return answer


def stream(scene: str, question: str, chunks=None, session_id: str = None):
    """流式生成，yield (delta) / (None, done) 标记。"""
    if scene == "agent":
        from app import agent
        yield from agent.stream_agent(get_model(), question, chunks, session_id)
        return
    msgs = _assemble(scene, question, chunks, session_id)
    model = get_model()
    full = ""
    start = time.time()
    acquired = _SEMAPHORE.acquire(timeout=_SEMAPHORE_TIMEOUT)
    if not acquired:
        raise RuntimeError("AI 服务繁忙，请稍后重试")
    try:
        for chunk in model.stream(msgs):
            if chunk.content:
                full += chunk.content
                yield chunk.content
        stats.record(scene, True, int((time.time() - start) * 1000))
    except Exception as e:  # noqa: BLE001
        stats.record(scene, False, int((time.time() - start) * 1000))
        raise RuntimeError(f"模型调用失败: {e}") from e
    finally:
        _SEMAPHORE.release()
    if session_id and _memorable(scene):
        memory.add(session_id, "user", question)
        memory.add(session_id, "assistant", full)
        memory.maybe_compress(session_id, _summarizer)


def _call_with_limit(fn, scene: str):
    """并发限制 + 调用统计包装。"""
    acquired = _SEMAPHORE.acquire(timeout=_SEMAPHORE_TIMEOUT)
    if not acquired:
        raise RuntimeError("AI 服务繁忙，请稍后重试")
    start = time.time()
    try:
        result = fn()
        stats.record(scene, True, int((time.time() - start) * 1000))
        return result
    except Exception:
        stats.record(scene, False, int((time.time() - start) * 1000))
        raise
    finally:
        _SEMAPHORE.release()


def _ensure_json_array(text: str) -> str:
    cleaned = _strip_code_fence(text)
    try:
        json.loads(cleaned)
        return cleaned
    except json.JSONDecodeError:
        m = re.search(r"\[.*\]", cleaned, re.S)
        if m:
            return m.group(0)
        raise RuntimeError("模型未输出合法 JSON 数组")


def _ensure_json_object(text: str) -> str:
    cleaned = _strip_code_fence(text)
    try:
        json.loads(cleaned)
        return cleaned
    except json.JSONDecodeError:
        m = re.search(r"\{.*\}", cleaned, re.S)
        if m:
            return m.group(0)
        raise RuntimeError("模型未输出合法 JSON 对象")


def _strip_code_fence(text: str) -> str:
    return re.sub(r"^```(?:json)?\s*|\s*```$", "", text.strip()).strip()
