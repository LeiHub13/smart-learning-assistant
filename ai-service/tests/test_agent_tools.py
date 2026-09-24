"""Agent 工具集：只读工具默认挂载，写工具按开关挂载，课程上下文由请求绑定。"""
from app import agent, config


def _names(tools):
    return {t.name for t in tools}


def test_write_tools_hidden_by_default(monkeypatch):
    monkeypatch.setattr(config, "AGENT_WRITE_TOOLS", False)
    names = _names(agent._make_tools(["资料A"], user_id=1, course_id=9))
    # 默认为 9 个只读工具：新加的 query_notes/query_favorites/query_question_bank 也是只读，
    # save_material_to_kb 等写工具开关关闭时不得出现
    assert names == {"list_material_topics", "search_materials", "query_wrong_book",
                     "query_mastery", "query_recent_practices", "query_kb_documents",
                     "query_notes", "query_favorites", "query_question_bank"}
    assert "save_material_to_kb" not in names and "open_page" not in names
    assert "待确认动作" not in agent._system_prompt([])


def test_write_tools_mounted_when_enabled(monkeypatch):
    monkeypatch.setattr(config, "AGENT_WRITE_TOOLS", True)
    names = _names(agent._make_tools([], user_id=1, course_id=9))
    assert {"query_plan_tasks", "schedule_review", "finish_plan_task", "save_material_to_kb",
            "add_note", "favorite_question", "generate_questions", "open_page"} <= names
    prompt = agent._system_prompt([])
    assert "schedule_review" in prompt and "open_page" in prompt
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
    assert "未提供用户信息" in tools["add_note"].invoke({"title": "T", "content": "C"})
    assert "未提供用户信息" in tools["favorite_question"].invoke({"question_id": 1})
    assert "未提供用户信息" in tools["generate_questions"].invoke({})
    assert "未提供用户信息" in tools["open_page"].invoke({"page": "notes"})
    # 无用户上下文时只能原地拒绝，不得发起任何 HTTP 回调
    assert calls == []


def test_course_scoped_write_tools_require_course_context(monkeypatch):
    """记笔记/收藏/出题是课程域动作：自由对话（无课程）时必须原地拒绝，不得发起回调。"""
    monkeypatch.setattr(config, "AGENT_WRITE_TOOLS", True)
    calls = []
    monkeypatch.setattr(agent, "_post_java_tool",
                        lambda path, payload: calls.append(path) or "不应被调用")
    tools = {t.name: t for t in agent._make_tools([], user_id=1, course_id=None)}
    assert "未绑定课程" in tools["add_note"].invoke({"title": "T", "content": "C"})
    assert "未绑定课程" in tools["favorite_question"].invoke({"question_id": 1})
    assert "未绑定课程" in tools["generate_questions"].invoke({})
    assert calls == []
    # open_page 是全局导航，不依赖课程，允许发起 propose
    assert "未绑定课程" not in tools["open_page"].invoke({"page": "notes"})
    assert calls == ["/internal/tools/actions/propose"]


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


def test_new_read_tools_call_java_with_request_context():
    """query_notes/query_favorites/query_question_bank 的 userId/courseId 由闭包绑定。"""
    calls = []
    original = agent._call_java_tool
    agent._call_java_tool = lambda path: calls.append(path) or "captured"
    try:
        tools = {t.name: t for t in agent._make_tools([], user_id=1, course_id=9)}
        tools["query_notes"].invoke({})
        tools["query_favorites"].invoke({})
        tools["query_question_bank"].invoke({"keyword": "哈希表"})
        assert calls == ["/internal/tools/notes?userId=1&courseId=9",
                         "/internal/tools/favorites?userId=1&courseId=9",
                         "/internal/tools/questions?courseId=9&keyword=%E5%93%88%E5%B8%8C%E8%A1%A8&limit=10"]
        # 自由对话（无课程）：笔记/收藏可全量查，题库必须拒绝
        calls.clear()
        free = {t.name: t for t in agent._make_tools([], user_id=1, course_id=None)}
        free["query_notes"].invoke({})
        free["query_favorites"].invoke({})
        assert "未绑定课程" in free["query_question_bank"].invoke({"keyword": "x"})
        assert calls == ["/internal/tools/notes?userId=1",
                         "/internal/tools/favorites?userId=1"]
    finally:
        agent._call_java_tool = original


def test_new_write_tools_propose_with_request_context(monkeypatch):
    """新写工具与旧写工具同构：一律经 propose 登记，上下文 ID 来自闭包。"""
    monkeypatch.setattr(config, "AGENT_WRITE_TOOLS", True)
    calls = []

    def fake_post(path, payload):
        calls.append((path, payload))
        return '{"actionId": 9, "summary": "已登记"}'

    monkeypatch.setattr(agent, "_post_java_tool", fake_post)
    tools = {t.name: t for t in agent._make_tools([], user_id=3, course_id=9, session_id="12")}

    out1 = tools["add_note"].invoke({"title": "动态规划", "content": "要点…", "kp_name": "DP"})
    out2 = tools["favorite_question"].invoke({"question_id": 77})
    out3 = tools["generate_questions"].invoke({"kp": "索引", "count": 8})
    out4 = tools["open_page"].invoke({"page": "Mistakes"})

    assert [p["kind"] for _, p in calls] == [
        "add_note", "favorite_question", "generate_questions", "open_page"]
    assert calls[0][1]["payload"] == {"title": "动态规划", "content": "要点…", "kpName": "DP"}
    assert calls[1][1]["payload"] == {"questionId": 77}
    assert calls[2][1]["payload"] == {"kp": "索引", "count": 8}
    # 页面标识原样透传，大小写由服务端白名单归一
    assert calls[3][1]["payload"] == {"page": "Mistakes"}
    for path, p in calls:
        assert path == "/internal/tools/actions/propose"
        assert p["userId"] == 3 and p["courseId"] == 9 and p["sessionId"] == 12
    for out in (out1, out2, out3, out4):
        assert "待确认动作" in out and "确认执行" in out
    # 工具回执里「已生成待确认动作」是固定前缀；断言不得声称动作本身已完成
    assert "已创建学习笔记" not in out1 and "已收藏题目" not in out2
    assert "加入课程题库" not in out3 and "已打开「" not in out4
