"""DeepSeek 搜索协议、失败处理和 Agent 接入；测试不调用真实计费接口。"""
import io
import json
import urllib.error
from types import SimpleNamespace

import pytest
from langchain_core.messages import AIMessage, AIMessageChunk, ToolMessage

from app import agent, config, deepseek_search


class Response:
    def __init__(self, body):
        self.body = body if isinstance(body, bytes) else json.dumps(body, ensure_ascii=False).encode("utf-8")

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def read(self, limit):
        return self.body[:limit]


@pytest.fixture
def search_key(monkeypatch):
    monkeypatch.setattr(config, "DEEPSEEK_API_KEY", "test-only-key")
    monkeypatch.setattr(config, "DEEPSEEK_SEARCH_ENABLED", True)


def test_search_request_and_structured_results(monkeypatch, search_key):
    captured = []

    def fake_open(request, timeout):
        captured.append((request, timeout))
        return Response({"results": [
            {"title": "官方文档", "url": "https://example.org/docs?a=1&b=2",
             "summary": "较长的结果摘要", "snippet": "短摘要", "site_name": "官方站", "date": "2026-10-17T00:00:00Z"},
            {"title": "另一来源", "url": "https://example.com/article", "summary": "", "snippet": "备用摘要"},
            {"title": "无效链接", "url": "javascript:alert(1)", "summary": "不应出现"},
        ]})

    monkeypatch.setattr(deepseek_search.urllib.request, "urlopen", fake_open)
    output = deepseek_search.web_search.invoke({"query": "  最新资料  ", "count": 30})
    request, timeout = captured[0]
    assert request.full_url == "https://api.deepseek.com/v1/tools/search"
    assert request.get_method() == "POST"
    assert request.get_header("Authorization") == "Bearer test-only-key"
    assert request.get_header("Content-type") == "application/json"
    assert json.loads(request.data) == {"query": "最新资料", "count": 10, "need_content": False}
    assert timeout == deepseek_search.TIMEOUT_SECONDS
    assert "官方文档" in output and "较长的结果摘要" in output and "2026-10-17" in output
    assert "https://example.org/docs?a=1&b=2" in output and "备用摘要" in output
    assert "javascript:" not in output and "不应出现" not in output
    assert "test-only-key" not in output


def test_missing_key_and_invalid_query_do_not_send_requests(monkeypatch):
    monkeypatch.setattr(config, "DEEPSEEK_API_KEY", "")
    monkeypatch.setattr(deepseek_search.urllib.request, "urlopen", lambda *_a, **_kw: pytest.fail("不应发送请求"))
    assert "不能为空" in deepseek_search.search("  ")
    assert "128" in deepseek_search.search("测" * 129)
    assert "未配置" in deepseek_search.search("正常查询")


@pytest.mark.parametrize(("status", "message"), [
    (400, "无效"), (401, "鉴权"), (402, "余额"), (429, "频繁"), (408, "超时"), (504, "超时"), (500, "失败"),
])
def test_http_errors_are_reported_without_fallback_or_secret(monkeypatch, search_key, caplog, status, message):
    calls = []

    def fake_open(request, timeout):
        calls.append(request.full_url)
        raise urllib.error.HTTPError(request.full_url, status, "test-only-key", {}, None)

    monkeypatch.setattr(deepseek_search.urllib.request, "urlopen", fake_open)
    output = deepseek_search.search("搜索词")
    assert message in output
    assert calls == [deepseek_search.SEARCH_URL]
    assert "test-only-key" not in output + caplog.text


def test_empty_malformed_and_network_failure(monkeypatch, search_key):
    monkeypatch.setattr(deepseek_search.urllib.request, "urlopen", lambda *_a, **_kw: Response({"results": []}))
    assert "没有找到" in deepseek_search.search("无结果")
    monkeypatch.setattr(deepseek_search.urllib.request, "urlopen", lambda *_a, **_kw: Response({"results": [{"url": "file:///tmp/x"}]}))
    assert "未返回可用" in deepseek_search.search("无效链接")
    monkeypatch.setattr(deepseek_search.urllib.request, "urlopen", lambda *_a, **_kw: Response(b"not-json"))
    assert "无法解析" in deepseek_search.search("坏响应")

    def fail(*_a, **_kw):
        raise urllib.error.URLError("network unavailable")

    monkeypatch.setattr(deepseek_search.urllib.request, "urlopen", fail)
    assert "连接失败" in deepseek_search.search("网络错误")


def test_search_key_is_reused_only_for_official_https_model():
    assert config._search_key("search-key", "model-key", "https://other.example/v1") == "search-key"
    assert config._search_key("", "model-key", "https://api.deepseek.com/v1") == "model-key"
    assert config._search_key("", "model-key", "https://api.deepseek.com.attacker.example/v1") == ""
    assert config._search_key("", "model-key", "http://api.deepseek.com/v1") == ""
    assert config._search_key("", "model-key", "https://[invalid") == ""


def test_legacy_search_mcp_is_removed_but_other_servers_survive():
    legacy = {"command": "python", "args": ["-m", "mcp_servers.web_search_server"]}
    legacy_path = {"command": "python", "args": ["./mcp_servers/web_search_server.py"]}
    other = {"command": "python", "args": ["-m", "custom_server"]}
    assert config._parse_mcp_servers("") == {}
    assert config._parse_mcp_servers("none") == {}
    assert config._parse_mcp_servers("invalid-json") == {}
    assert config._parse_mcp_servers(json.dumps({"web-search": legacy, "path": legacy_path, "other": other})) == {"other": other}


def test_search_tool_coexists_with_custom_mcp_and_wins_name_collision(monkeypatch, search_key):
    duplicate = SimpleNamespace(name="web_search")
    other = SimpleNamespace(name="other_tool")
    monkeypatch.setattr(agent, "get_mcp_tools", lambda: [duplicate, other])
    direct, mcp = agent._external_tools()
    assert direct == [deepseek_search.web_search]
    assert mcp == [other]
    assert "web_search" in agent._system_prompt(mcp, web_search_enabled=bool(direct))
    assert "Markdown" in agent._system_prompt(mcp, web_search_enabled=True)
    monkeypatch.setattr(config, "DEEPSEEK_SEARCH_ENABLED", False)
    direct, mcp = agent._external_tools()
    assert direct == [] and mcp == [duplicate, other]
    assert "web_search" not in agent._system_prompt([], web_search_enabled=False)


def test_both_agent_paths_can_call_deepseek_search(monkeypatch, search_key):
    from app import chains, stats

    requests = []
    prompts = []
    calls = []

    def fake_open(request, timeout):
        requests.append(json.loads(request.data))
        return Response({"results": [{"title": "来源", "url": "https://example.org/", "summary": "资料"}]})

    monkeypatch.setattr(deepseek_search.urllib.request, "urlopen", fake_open)
    monkeypatch.setattr(agent, "get_mcp_tools", lambda: [])
    monkeypatch.setattr(agent, "_remember", lambda *_args: None)
    monkeypatch.setattr(stats, "record", lambda *_args, **_kwargs: None)
    monkeypatch.setattr(chains, "_attach_followups", lambda *_args: None)

    class FakeModel:
        def with_config(self, **_kwargs):
            return self

    class FakeAgent:
        def __init__(self, tools):
            self.search = next(t for t in tools if t.name == "web_search")

        def invoke(self, *_args, **_kwargs):
            calls.append(self.search.invoke({"query": "最近动态"}))
            return {"messages": [AIMessage(content="回答")]}

        def stream(self, *_args, **_kwargs):
            calls.append(self.search.invoke({"query": "实时信息"}))
            yield AIMessageChunk(content="流式回答")

    def fake_create(_model, tools, system_prompt):
        prompts.append(system_prompt)
        return FakeAgent(tools)

    monkeypatch.setattr(agent, "create_agent", fake_create)
    assert agent.complete_agent(FakeModel(), "请搜索") == "回答"
    assert "".join(agent.stream_agent(FakeModel(), "请搜索")) == "流式回答"
    assert [r["query"] for r in requests] == ["最近动态", "实时信息"]
    assert all("https://example.org/" in result for result in calls)
    assert all("web_search" in prompt for prompt in prompts)


def test_actual_langchain_agent_invokes_search_tool(monkeypatch, search_key):
    """用真实 Agent 循环处理模型工具调用，搜索 API 响应仍使用隔离替身。"""
    from app import stats

    monkeypatch.setattr(deepseek_search.urllib.request, "urlopen", lambda *_a, **_kw: Response({
        "results": [{"title": "参考资料", "url": "https://example.org/source", "summary": "内容摘要"}]
    }))
    monkeypatch.setattr(agent, "get_mcp_tools", lambda: [])
    monkeypatch.setattr(agent, "_remember", lambda *_args: None)
    monkeypatch.setattr(stats, "record", lambda *_args, **_kwargs: None)

    class FakeModel:
        def __init__(self):
            self.calls = 0
            self.observed = ""

        def with_config(self, **_kwargs):
            return self

        def bind_tools(self, tools, **_kwargs):
            assert any(t.name == "web_search" for t in tools)
            return self

        def invoke(self, messages, **_kwargs):
            self.calls += 1
            if self.calls == 1:
                return AIMessage(content="", tool_calls=[{
                    "name": "web_search", "args": {"query": "最新资料"}, "id": "search-1"
                }])
            self.observed = next(m.content for m in messages if isinstance(m, ToolMessage))
            return AIMessage(content="[参考资料](https://example.org/source)")

    model = FakeModel()
    assert agent.complete_agent(model, "查找最新资料") == "[参考资料](https://example.org/source)"
    assert model.calls == 2
    assert "内容摘要" in model.observed and "https://example.org/source" in model.observed


def test_health_exposes_only_configuration_status(monkeypatch, search_key):
    from app import main
    response = main.health()["webSearch"]
    assert response == {"provider": "deepseek", "enabled": True, "configured": True}
    assert "test-only-key" not in repr(response)
