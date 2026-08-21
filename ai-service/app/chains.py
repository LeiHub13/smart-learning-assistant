"""场景链：基于 langchain 的提示词模板 + 模型调用（流式/非流式）。"""
import json
import re

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage
from langchain_openai import ChatOpenAI

from app import config, memory

_MODEL = None


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
                "answer(参考答案；多选答案如\"AB\"), analysis(解析), kpName(知识点)。"
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
    raise ValueError(f"未知场景: {scene}")


def complete(scene: str, question: str, chunks=None, session_id: str = None) -> str:
    """非流式完整回答（生成/批改/建议），带记忆。"""
    if scene == "agent":
        from app import agent
        return agent.complete_agent(get_model(), question, chunks, session_id)
    msgs = _build_messages(scene, question, chunks)
    if session_id and scene in ("rag_qa", "free"):
        msgs = [msgs[0]] + memory.recent(session_id) + [msgs[-1]]
    answer = get_model().invoke(msgs).content
    if session_id and scene in ("rag_qa", "free"):
        memory.add(session_id, "user", question)
        memory.add(session_id, "assistant", answer)
    if scene == "questions":
        return _ensure_json_array(answer)
    if scene == "review":
        return _ensure_json_object(answer)
    return answer


def stream(scene: str, question: str, chunks=None, session_id: str = None):
    """流式生成，yield (delta) / (None, done) 标记。"""
    if scene == "agent":
        from app import agent
        yield from agent.stream_agent(get_model(), question, chunks, session_id)
        return
    msgs = _build_messages(scene, question, chunks)
    if session_id and scene in ("rag_qa", "free"):
        msgs = [msgs[0]] + memory.recent(session_id) + [msgs[-1]]
    model = get_model()
    full = ""
    try:
        for chunk in model.stream(msgs):
            if chunk.content:
                full += chunk.content
                yield chunk.content
    except Exception as e:  # noqa: BLE001
        raise RuntimeError(f"模型调用失败: {e}") from e
    if session_id and scene in ("rag_qa", "free"):
        memory.add(session_id, "user", question)
        memory.add(session_id, "assistant", full)


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