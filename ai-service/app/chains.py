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
- recommend         今日推荐（基于规则引擎候选条目，JSON 数组）
"""
import json
import logging
import os
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


def _note_suffix(note: str | None) -> str:
    """Java 侧随请求附带的学情说明（如薄弱知识点），拼在系统提示末尾。"""
    return ("\n" + note.strip()) if note and note.strip() else ""


def _kb_suffix(kb_name: str | None, kb_scope: str | None) -> str:
    """会话选中的知识库上下文：kbId 只是数字，模型要靠名称才能回答「当前选的是哪个库」。"""
    if not kb_name or not kb_name.strip():
        return ""
    scope = "本课程全部知识库" if (kb_scope or "").strip() == "course" else "仅该知识库"
    return (f"\n【会话上下文】学生当前选中的知识库：「{kb_name.strip()}」，检索范围：{scope}；"
            "list_material_topics / search_materials 与引用来源都以此为准。")


def _build_messages(scene: str, question: str, chunks, note: str = None,
                    kb_name: str = None, kb_scope: str = None) -> list:
    chunks_text = _chunks_to_text(chunks)
    if scene == "rag_qa":
        return [
            SystemMessage(
                "你是智能学习助手，请结合下方课程知识库资料回答学生问题，引用资料时标注编号[1][2]等；"
                "若资料与问题无关则如实说明。\n【知识库资料】\n" + chunks_text + "\n【资料结束】"
                + _kb_suffix(kb_name, kb_scope) + _note_suffix(note)
            ),
            HumanMessage(question),
        ]
    if scene == "free":
        return [
            SystemMessage("你是智能学习助手，用中文友好、准确地解答学生的学习问题。"
                          + _kb_suffix(kb_name, kb_scope) + _note_suffix(note)),
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
    if scene == "recommend":
        return [
            SystemMessage(
                "你是学习推荐师。规则引擎已根据学生真实学习数据（掌握度、遗忘衰减、知识库资料）"
                "生成候选条目，请据此为学生编排今日推荐。要求：\n"
                "1. 只能从候选条目中挑选与排序，可润色 title 和 reason 使其更具体、更贴近学生、更有行动力，"
                "但不得编造候选之外的知识点、数字或资料；\n"
                "2. type 为 document 的条目，reason 必须原样保留候选中的资料文本（含来源标注），不得改写；\n"
                "3. 输出不超过 6 条，按紧急程度从高到低排列（最该先做的在前）；\n"
                "4. 只输出 JSON 数组，不要输出任何其他文字。每项字段："
                "type(startup/review_kp/practice_kp/document), title(字符串), reason(字符串), "
                "kpName(知识点名，无则为 null)。"
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


# 进入画像提炼的单轮问答截断长度（防止长回答撑爆提炼提示词）
PROFILE_TEXT_LIMIT = 500

# 画像提炼并发闸门：提炼调用不走 _call_with_limit 的场景信号量，须自限并发，
# 避免突发流量下无限开线程、无限并发打 LLM
_PROFILE_CONCURRENCY = int(os.getenv("AI_PROFILE_CONCURRENCY", "2"))
_PROFILE_SLOTS = threading.BoundedSemaphore(_PROFILE_CONCURRENCY)


def _extract_profile(user_id, question: str, answer: str) -> None:
    """后台线程：把本轮答疑中的长期有效信息合并进学生画像（跨会话记忆）。

    失败只记日志；寒暄类过短轮次跳过，不值得花一次 LLM 调用。
    """
    try:
        if len(question) + len(answer) < 40:
            return
        old = memory.user_profile(user_id)
        prompt = (
            "你是学生画像维护器。把下面这轮答疑反映的【长期有效】信息合并进学生画像："
            "学习目标、学习偏好（如喜欢先看例子再听原理）、反复出现的薄弱知识点、已有基础。"
            "不要记录一次性题目细节、具体答案本身或寒暄。若本轮没有新的长期信息，原样输出画像。\n"
            "只输出画像本身，用短句分条，不超过 300 字。\n"
            + (f"【当前画像】\n{old}\n" if old else "【当前画像】（暂空）\n")
            + f"【本轮答疑】\n学生：{question[:PROFILE_TEXT_LIMIT]}\n助手：{answer[:PROFILE_TEXT_LIMIT]}"
        )
        updated = get_model().invoke([HumanMessage(prompt)]).content.strip()
        if updated and updated != old:
            memory.save_user_profile(user_id, updated)
    except Exception as e:  # noqa: BLE001
        logger.warning("用户画像提炼失败（忽略，userId=%s）: %s", user_id, e)


def _extract_profile_limited(user_id, question: str, answer: str) -> None:
    try:
        _extract_profile(user_id, question, answer)
    finally:
        _PROFILE_SLOTS.release()


def _remember(session_id: str, user_id, question: str, answer: str) -> None:
    """落会话记忆（成对追加 + 触发滚动压缩），并异步提炼用户长期画像。"""
    if session_id:
        memory.add(session_id, "user", question)
        memory.add(session_id, "assistant", answer)
        memory.maybe_compress(session_id, _summarizer)
    if user_id and _PROFILE_SLOTS.acquire(blocking=False):
        # 有限并发：闸门占满时直接放弃本轮提炼（旁路任务，合并式 prompt 会由后续轮次补上），
        # 不排队、不无限开线程；守护线程随进程退出，不阻塞服务关闭
        threading.Thread(target=_extract_profile_limited, args=(user_id, question, answer),
                         daemon=True, name="profile-extract").start()


def _assemble(scene: str, question: str, chunks, session_id: str, note: str = None,
              kb_name: str = None, kb_scope: str = None, user_id=None) -> list:
    """组装消息：系统提示 + （学生画像）+ （摘要）+ 最近 N 轮 + 当前问题。"""
    msgs = _build_messages(scene, question, chunks, note, kb_name=kb_name, kb_scope=kb_scope)
    if session_id and scene in ("rag_qa", "free"):
        head = [msgs[0]]
        profile = memory.user_profile(user_id) if user_id else ""
        if profile:
            head.append(SystemMessage("【学生长期画像（跨会话记忆，供个性化参考）】\n" + profile))
        s = memory.summary(session_id)
        if s:
            head.append(SystemMessage("【此前对话摘要】\n" + s))
        return head + memory.recent(session_id) + [msgs[-1]]
    return msgs


def _memorable(scene: str) -> bool:
    return scene in ("rag_qa", "free")


def complete_with(messages, scene: str = "rewrite") -> str:
    """直接以给定消息列表调用模型（供 RAG 流水线内部的改写/重排使用），带并发限制与统计。"""
    return _call_with_limit(lambda: get_model().invoke(messages).content, scene)


def _prepare_rag(scene: str, question: str, chunks, session_id, kb_id, meta: dict, kb_ids=None):
    """rag_qa 场景若未随请求携带 chunks，则由本服务自行完成向量检索。

    检索链路（查询改写 -> Chroma 召回 -> 重排）已自 Java 侧迁移至 app/rag.py；
    kb_ids 为课程级检索范围（本课程全部知识库 id），缺省只搜 kb_id 单库；
    引用编号 sources 通过 meta 回传给调用方（Java 落库）。
    """
    if scene != "rag_qa" or chunks:
        return chunks
    if not kb_id:
        meta.setdefault("sources", "")
        return None
    from app import rag
    ctx = rag.build_context(kb_id, question, memory.recent(session_id) if session_id else None,
                            kb_ids=kb_ids)
    meta["sources"] = ctx.sources
    return ctx.context


def complete(scene: str, question: str, chunks=None, session_id: str = None,
             user_id=None, kb_id=None, kb_ids=None, note: str = None, meta: dict | None = None,
             course_id=None, kb_name: str = None, kb_scope: str = None) -> str:
    """非流式完整回答（生成/批改/建议），带记忆。"""
    meta = meta if meta is not None else {}
    if scene == "agent":
        from app import agent
        return agent.complete_agent(get_model(), question, chunks=chunks, session_id=session_id,
                                    user_id=user_id, kb_id=kb_id, kb_ids=kb_ids, note=note, meta=meta,
                                    course_id=course_id, kb_name=kb_name, kb_scope=kb_scope)
    chunks = _prepare_rag(scene, question, chunks, session_id, kb_id, meta, kb_ids=kb_ids)
    msgs = _assemble(scene, question, chunks, session_id, note, kb_name=kb_name,
                     kb_scope=kb_scope, user_id=user_id)
    answer = _call_with_limit(lambda: get_model().invoke(msgs).content, scene)
    if _memorable(scene):
        _remember(session_id, user_id, question, answer)
    if scene in ("questions", "plan", "recommend"):
        return _ensure_json_array(answer)
    if scene in ("review", "rerank"):
        return _ensure_json_object(answer) if scene == "review" else _ensure_json_array(answer)
    return answer


def stream(scene: str, question: str, chunks=None, session_id: str = None,
           user_id=None, kb_id=None, kb_ids=None, note: str = None, meta: dict | None = None,
           course_id=None, kb_name: str = None, kb_scope: str = None):
    """流式生成，yield 增量文本；检索到的引用编号通过 meta["sources"] 传出。"""
    meta = meta if meta is not None else {}
    if scene == "agent":
        from app import agent
        yield from agent.stream_agent(get_model(), question, chunks=chunks, session_id=session_id,
                                      user_id=user_id, kb_id=kb_id, kb_ids=kb_ids, note=note, meta=meta,
                                      course_id=course_id, kb_name=kb_name, kb_scope=kb_scope)
        return
    chunks = _prepare_rag(scene, question, chunks, session_id, kb_id, meta, kb_ids=kb_ids)
    msgs = _assemble(scene, question, chunks, session_id, note, kb_name=kb_name,
                     kb_scope=kb_scope, user_id=user_id)
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
    if _memorable(scene):
        _remember(session_id, user_id, question, full)


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
