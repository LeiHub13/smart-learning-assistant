"""Agent 工具集：只读工具默认挂载，写工具按开关挂载，课程上下文由请求绑定。"""
from app import agent, config


def _names(tools):
    return {t.name for t in tools}


def test_write_tools_hidden_by_default(monkeypatch):
    monkeypatch.setattr(config, "AGENT_WRITE_TOOLS", False)
    names = _names(agent._make_tools(["资料A"], user_id=1, course_id=9))
    # 默认仍为 6 个只读工具：save_material_to_kb 属写工具，开关关闭时不得出现
    assert names == {"list_material_topics", "search_materials", "query_wrong_book",
                     "query_mastery", "query_recent_practices", "query_kb_documents"}
    assert "save_material_to_kb" not in names
    assert "待确认动作" not in agent._system_prompt([])


def test_write_tools_mounted_when_enabled(monkeypatch):
    monkeypatch.setattr(config, "AGENT_WRITE_TOOLS", True)
    names = _names(agent._make_tools([], user_id=1, course_id=9))
    assert {"query_plan_tasks", "schedule_review", "finish_plan_task",
            "save_material_to_kb"} <= names
    prompt = agent._system_prompt([])
    assert "schedule_review" in prompt
    # 写提示词必须讲清「只登记待确认动作」，否则模型会对学生谎称动作已生效
    assert "待确认动作" in prompt and "确认执行" in prompt


def test_course_id_comes_from_request_not_the_model():
    """query_mastery / query_kb_documents 不再接收模型填写的 course_id。"""
    calls = []
    original = agent._call_java_tool
    agent._call_java_tool = lambda path: calls.append(path) or "captured"
    try:
        tools = {t.name: t for t in agent._make_tools([], user_id=1, course_id=9)}
        assert tools["query_mastery"].args == {}
        tools["query_mastery"].invoke({})
        tools["query_kb_documents"].invoke({})
        assert calls == ["/internal/tools/mastery?userId=1&courseId=9",
                         "/internal/tools/kb-documents?courseId=9"]
    finally:
        agent._call_java_tool = original


def test_write_tools_require_user_context(monkeypatch):
    monkeypatch.setattr(config, "AGENT_WRITE_TOOLS", True)
    calls = []
    monkeypatch.setattr(agent, "_post_java_tool",
                        lambda path, payload: calls.append(path) or "不应被调用")
    tools = {t.name: t for t in agent._make_tools([], user_id=None, course_id=None)}
    assert "未提供用户信息" in tools["schedule_review"].invoke({"kp_name": "索引"})
    assert "未提供用户信息" in tools["finish_plan_task"].invoke({"task_id": 1})
    assert "未提供用户信息" in tools["save_material_to_kb"].invoke({"title": "T", "content": "C"})
    # 无用户上下文时只能原地拒绝，不得发起任何 HTTP 回调
    assert calls == []


def test_save_material_proposes_with_closure_context(monkeypatch):
    """写动作改为 proposal：工具只登记待确认动作，上下文 ID 来自闭包而非模型。"""
    monkeypatch.setattr(config, "AGENT_WRITE_TOOLS", True)
    calls = []

    def fake_post(path, payload):
        calls.append((path, payload))
        return '{"actionId": 7, "summary": "存资料进知识库：《T》→ 课程资料"}'

    monkeypatch.setattr(agent, "_post_java_tool", fake_post)
    tools = {t.name: t for t in agent._make_tools(
        [], user_id=3, kb_id=5, course_id=9, session_id="12")}
    out = tools["save_material_to_kb"].invoke({"title": "T", "content": "C"})

    assert len(calls) == 1
    path, payload = calls[0]
    assert path == "/internal/tools/actions/propose"
    assert payload["userId"] == 3 and payload["courseId"] == 9
    assert payload["sessionId"] == 12
    assert payload["kind"] == "add_material"
    assert payload["payload"] == {"title": "T", "content": "C", "kbId": 5}
    assert "待确认动作" in out and "确认执行" in out
    assert "已保存" not in out


def test_review_and_checkin_also_go_through_propose(monkeypatch):
    """复习提醒与打卡同样只能登记待确认动作：直写端点已删除，任何调用都不得再指向它们。"""
    monkeypatch.setattr(config, "AGENT_WRITE_TOOLS", True)
    calls = []

    def fake_post(path, payload):
        calls.append((path, payload))
        return '{"actionId": 8, "summary": "已登记"}'

    monkeypatch.setattr(agent, "_post_java_tool", fake_post)
    tools = {t.name: t for t in agent._make_tools(
        [], user_id=3, course_id=9, session_id="12")}

    out1 = tools["schedule_review"].invoke({"kp_name": "索引", "remind_at": "2026-09-26", "note": ""})
    out2 = tools["finish_plan_task"].invoke({"task_id": 41})

    assert [c[0] for c in calls] == ["/internal/tools/actions/propose"] * 2
    assert calls[0][1]["kind"] == "schedule_review"
    assert calls[0][1]["payload"] == {"kpName": "索引", "remindAt": "2026-09-26", "note": ""}
    assert calls[1][1]["kind"] == "finish_plan_task"
    assert calls[1][1]["payload"] == {"taskId": 41}
    for _, p in calls:
        assert p["userId"] == 3 and p["courseId"] == 9 and p["sessionId"] == 12
    assert "已安排" not in out1 and "打卡成功" not in out2
    assert "待确认动作" in out1 and "待确认动作" in out2
