"""Python 答疑 API：会话、消息、Agent 流和异步结算。"""
import asyncio
import hmac
import json
import logging
import queue
import threading
import uuid

from fastapi import APIRouter, Header
from pydantic import BaseModel
from sse_starlette.sse import EventSourceResponse

from app import chains, chat_java, chat_store, config, memory

logger = logging.getLogger("ai-service.chat")
router = APIRouter()
_reconcile_stop = threading.Event()
_reconcile_thread = None


class SessionInput(BaseModel):
    title: str | None = None
    courseId: int | None = None
    kbId: int | None = None
    kbScope: str | None = None
    socratic: bool | None = None
    feynman: bool | None = None


class MessageInput(BaseModel):
    message: str | None = None


def _ok(data=None):
    return {"code": 0, "message": "ok", "data": data}


def _user(authorization):
    return chat_java.authenticate(authorization)


def _check_internal(token):
    if not token or not hmac.compare_digest(token.encode("utf-8"), config.JAVA_TOOL_TOKEN.encode("utf-8")):
        raise chat_java.JavaBridgeError("内部接口鉴权失败", 403)


def _context(user_id, session):
    return chat_java.context(user_id, session.get("courseId"), session.get("kbId"), session.get("kbScope"))


@router.get("/api/chat/sessions")
def list_sessions(authorization: str | None = Header(default=None)):
    return _ok(chat_store.sessions(_user(authorization)))


@router.post("/api/chat/sessions")
def create_session(body: SessionInput, authorization: str | None = Header(default=None)):
    user_id = _user(authorization)
    context = chat_java.context(user_id, body.courseId, body.kbId, body.kbScope)
    return _ok(chat_store.create_session(user_id, context.get("courseId"), body.kbId, body.kbScope))


@router.put("/api/chat/sessions/{session_id}")
def update_session(session_id: int, body: SessionInput, authorization: str | None = Header(default=None)):
    user_id = _user(authorization)
    current = chat_store.session(session_id, user_id)
    values = body.model_dump(exclude_unset=True)
    if any(values.get(key) is not None for key in ("courseId", "kbId", "kbScope")):
        course_id = values.get("courseId") if values.get("courseId") is not None else current["courseId"]
        kb_id = values.get("kbId") if values.get("kbId") is not None else current["kbId"]
        scope = values.get("kbScope") if values.get("kbScope") is not None else current["kbScope"]
        resolved = chat_java.context(user_id, course_id, kb_id, scope)
        if course_id is None and resolved.get("courseId") is not None:
            values["courseId"] = resolved["courseId"]
    return _ok(chat_store.update_session(session_id, user_id, values))


@router.get("/api/chat/sessions/{session_id}/messages")
def list_messages(session_id: int, authorization: str | None = Header(default=None)):
    return _ok(chat_store.messages(session_id, _user(authorization)))


@router.delete("/api/chat/sessions/{session_id}")
def delete_session(session_id: int, authorization: str | None = Header(default=None)):
    chat_store.delete_session(session_id, _user(authorization))
    memory.clear(str(session_id))
    return _ok()


@router.get("/ai/internal/chat/sessions/{session_id}/messages")
def internal_messages(session_id: int, userId: int, x_internal_token: str | None = Header(default=None)):
    _check_internal(x_internal_token)
    return _ok(chat_store.messages(session_id, userId))


@router.delete("/ai/internal/chat/courses/{course_id}/sessions")
def internal_delete_course(course_id: int, x_internal_token: str | None = Header(default=None)):
    _check_internal(x_internal_token)
    ids = chat_store.delete_course_sessions(course_id)
    for session_id in ids:
        memory.clear(str(session_id))
    return _ok({"deleted": len(ids)})


class _Subscriber:
    def __init__(self):
        self.items = queue.Queue()
        self.lock = threading.Lock()
        self.connected = True

    def emit(self, event):
        with self.lock:
            if self.connected:
                self.items.put_nowait(event)

    def close(self):
        with self.lock:
            self.connected = False


def _reconcile_one(row):
    if row["state"] == "SUCCESS_SETTLE":
        chat_java.settle(row["reservation_id"])
    else:
        # 即使 reserve 响应丢失，也用稳定轮次 ID 撤销可能已产生的预留。
        chat_java.release(row["user_id"], row["id"])
    chat_store.finish_reconciliation(row["id"], row["state"])


def _sync_turn_memory(turn_id):
    saved = chat_store.sync_turn_memory(turn_id, memory.add_turn_once)
    if saved:
        # 摘要与画像是旁路增强；主消息与会话记忆已经保存。
        try:
            chains._after_remember(saved["sessionId"], saved["userId"], saved["question"], saved["answer"])
        except Exception:
            logger.warning("会话记忆的摘要或画像更新失败", exc_info=True)


def reconcile_pending():
    for row in chat_store.pending_reconciliation():
        try:
            _reconcile_one(row)
        except Exception:
            logger.warning("答疑轮次 %s 的次数结算待重试", row["id"], exc_info=True)
            try:
                chat_store.defer_reconciliation(row["id"], row["state"])
            except Exception:
                logger.warning("答疑轮次 %s 暂时无法延期重试", row["id"], exc_info=True)
    for turn_id in chat_store.pending_memory_sync():
        try:
            _sync_turn_memory(turn_id)
        except Exception:
            logger.warning("答疑轮次 %s 的会话记忆待重试", turn_id, exc_info=True)
    for row in chat_store.pending_memory_cleanup():
        try:
            memory.clear(str(row["session_id"]))
            chat_store.finish_memory_cleanup(row["session_id"], row["updated_at"])
        except Exception:
            logger.warning("会话 %s 的记忆清理待重试", row["session_id"], exc_info=True)


def _reconcile_loop():
    while not _reconcile_stop.wait(10):
        try:
            reconcile_pending()
        except Exception:
            logger.warning("答疑轮次恢复任务暂不可用", exc_info=True)


def start_reconciler():
    global _reconcile_thread
    _reconcile_stop.clear()
    _reconcile_thread = threading.Thread(target=_reconcile_loop, name="chat-reconcile", daemon=True)
    _reconcile_thread.start()


def stop_reconciler():
    _reconcile_stop.set()
    if _reconcile_thread is not None:
        _reconcile_thread.join(timeout=2)


def _fail_turn(turn_id, user_id):
    try:
        state = chat_store.fail_turn(turn_id)
        if state == "FAILED_RELEASE":
            _reconcile_one({"id": turn_id, "user_id": user_id, "state": state})
    except Exception:
        # 数据库状态或 Java 不可用时，保留轮次供后台重试；不能盲目退款。
        logger.warning("答疑轮次 %s 退款待恢复", turn_id, exc_info=True)


def _heartbeat(turn_id, stop):
    while not stop.wait(30):
        try:
            chat_store.touch_turn(turn_id)
        except Exception:
            logger.warning("答疑轮次 %s 续期失败", turn_id, exc_info=True)


def _generate(turn_id, user_id, session, context, question, old_action_ids, subscriber):
    session_id = session["id"]
    stop = threading.Event()
    heartbeat = threading.Thread(target=_heartbeat, args=(turn_id, stop), daemon=True)
    heartbeat.start()
    answer = []
    meta = {}
    try:
        scene = "agent" if config.CHAT_AGENT_ENABLED else ("rag_qa" if context.get("kbId") else "free")
        for delta in chains.stream(
            scene, question, session_id=str(session_id), user_id=user_id,
            kb_id=context.get("kbId"), kb_ids=context.get("kbIds") or None,
            note=context.get("note"), meta=meta, course_id=context.get("courseId"),
            kb_name=context.get("kbName"), kb_scope=session.get("kbScope"),
            socratic=session.get("socratic", False), feynman=session.get("feynman", False), remember=False,
        ):
            answer.append(delta)
            subscriber.emit({"delta": delta})
        result = {"sources": meta.get("sources") or "", "sourceChunks": meta.get("sourceChunks") or "",
                  "followups": json.dumps(meta.get("followups") or [], ensure_ascii=False)}
        state = chat_store.finish_turn(turn_id, session_id, user_id, "".join(answer), result)
    except Exception:
        logger.exception("答疑生成或保存失败: session=%s", session_id)
        _fail_turn(turn_id, user_id)
        try:
            chat_store.session(session_id, user_id)
        except chat_store.SessionNotFound:
            try:
                memory.clear(str(session_id))
                chat_store.finish_memory_cleanup(session_id)
            except Exception:
                logger.warning("已删除会话的记忆将在后台清理", exc_info=True)
        except Exception:
            logger.warning("无法检查已删除会话的记忆", exc_info=True)
        subscriber.emit({"error": "生成失败，请重试"})
        return
    finally:
        stop.set()

    try:
        _sync_turn_memory(turn_id)
    except Exception:
        logger.warning("回答已保存，会话记忆将后台重试: %s", turn_id, exc_info=True)
    if state == "SUCCESS_SETTLE":
        try:
            _reconcile_one({"id": turn_id, "user_id": user_id, "state": state, "reservation_id": turn_id})
        except Exception:
            logger.warning("回答已保存，次数结算将后台重试: %s", turn_id, exc_info=True)
    actions = []
    if old_action_ids is not None:
        try:
            actions = [item for item in chat_java.pending_actions(user_id, session_id)
                       if item.get("id") not in old_action_ids]
        except Exception:
            logger.warning("查询待确认动作失败", exc_info=True)
    result["actions"] = actions
    subscriber.emit(result)


@router.post("/api/chat/sessions/{session_id}/stream")
def stream_message(session_id: int, body: MessageInput, authorization: str | None = Header(default=None)):
    user_id = _user(authorization)
    question = (body.message or "").strip()
    if not question:
        raise chat_store.ChatStoreError("请输入问题")
    session = chat_store.session(session_id, user_id)
    context = _context(user_id, session)
    turn_id = str(uuid.uuid4())
    chat_store.begin_turn(turn_id, session_id, user_id)
    try:
        reservation = chat_java.reserve(user_id, turn_id)
        chat_store.start_turn(turn_id, session_id, user_id, question, reservation)
    except Exception:
        _fail_turn(turn_id, user_id)
        raise
    try:
        previous = {item.get("id") for item in chat_java.pending_actions(user_id, session_id)}
    except Exception:
        previous = None
        logger.warning("查询已有待确认动作失败", exc_info=True)
    subscriber = _Subscriber()

    def run():
        try:
            _generate(turn_id, user_id, session, context, question, previous, subscriber)
        finally:
            subscriber.emit(None)

    threading.Thread(target=run, name="chat-" + turn_id, daemon=True).start()

    async def events():
        try:
            while True:
                item = await asyncio.to_thread(subscriber.items.get)
                if item is None:
                    return
                yield {"data": json.dumps(item, ensure_ascii=False)}
        finally:
            # 客户端断线只停止推送，生成线程继续保存和结算。
            subscriber.close()

    # Web 客户端按 \n\n 分帧；默认 CRLF 会使它无法识别事件边界。
    return EventSourceResponse(events(), ping=None, sep="\n")
