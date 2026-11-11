"""答疑服务访问 Java 所有的身份、课程、动作和计费能力。"""
import json
import urllib.error
import urllib.parse
import urllib.request

from app import config

_direct = urllib.request.build_opener(urllib.request.ProxyHandler({}))


class JavaBridgeError(Exception):
    def __init__(self, message, code=503):
        super().__init__(message)
        self.code = code


def _request(path, *, body=None, authorization=None, internal=False):
    headers = {"Accept": "application/json"}
    if authorization:
        headers["Authorization"] = authorization
    if internal:
        headers["X-Internal-Token"] = config.JAVA_TOOL_TOKEN
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(config.JAVA_TOOL_BASE.rstrip("/") + path, data=data, headers=headers)
    try:
        # 内部地址与用户 JWT 不能经进程环境中的外部 HTTP 代理转发。
        with _direct.open(request, timeout=15) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        if exc.code == 401:
            raise JavaBridgeError("未登录或登录已过期", 401) from exc
        raise JavaBridgeError("业务服务暂时不可用，请稍后重试") from exc
    except (OSError, ValueError) as exc:
        raise JavaBridgeError("业务服务暂时不可用，请稍后重试") from exc
    if payload.get("code") != 0:
        raise JavaBridgeError(payload.get("message") or "业务处理失败", payload.get("code") or 500)
    return payload.get("data")


def authenticate(authorization):
    if not authorization or not authorization.startswith("Bearer "):
        raise JavaBridgeError("未登录或登录已过期", 401)
    user = _request("/api/auth/me", authorization=authorization)
    try:
        return int(user["id"])
    except (KeyError, TypeError, ValueError) as exc:
        raise JavaBridgeError("无法确认用户身份") from exc


def context(user_id, course_id=None, kb_id=None, kb_scope=None):
    params = {"userId": user_id}
    for name, value in (("courseId", course_id), ("kbId", kb_id), ("kbScope", kb_scope)):
        if value is not None:
            params[name] = value
    return _request("/internal/chat/context?" + urllib.parse.urlencode(params), internal=True)


def pending_actions(user_id, session_id):
    query = urllib.parse.urlencode({"userId": user_id, "sessionId": session_id})
    return _request("/internal/chat/actions?" + query, internal=True) or []


def reserve(user_id, turn_id):
    result = _request("/internal/chat/billing/reserve", body={"userId": user_id, "turnId": turn_id}, internal=True)
    return result.get("reservationId")


def settle(reservation):
    _request(f"/internal/chat/billing/{reservation}/settle", body={}, internal=True)


def release(user_id, turn_id):
    _request(f"/internal/chat/billing/{turn_id}/release", body={"userId": user_id}, internal=True)
