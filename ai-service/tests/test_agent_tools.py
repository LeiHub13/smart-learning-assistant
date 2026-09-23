"""Agent 工具集：只读工具默认挂载，写工具按开关挂载，课程上下文由请求绑定。"""
from app import agent, config


def _names(tools):
    return {t.name for t in tools}


def test_write_tools_hidden_by_default(monkeypatch):
    monkeypatch.setattr(config, "AGENT_WRITE_TOOLS", False)
    names = _names(agent._make_tools(["资料A"], user_id=1, course_id=9))
    assert names == {"list_material_topics", "search_materials", "query_wrong_book",
                     "query_mastery", "query_recent_practices", "query_kb_documents"}
    assert "做出实际动作" not in agent._system_prompt([])


def test_write_tools_mounted_when_enabled(monkeypatch):
    monkeypatch.setattr(config, "AGENT_WRITE_TOOLS", True)
    names = _names(agent._make_tools([], user_id=1, course_id=9))
    assert {"query_plan_tasks", "schedule_review", "finish_plan_task"} <= names
    assert "schedule_review" in agent._system_prompt([])


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
    tools = {t.name: t for t in agent._make_tools([], user_id=None, course_id=None)}
    assert "未提供用户信息" in tools["schedule_review"].invoke({"kp_name": "索引"})
    assert "未提供用户信息" in tools["finish_plan_task"].invoke({"task_id": 1})
