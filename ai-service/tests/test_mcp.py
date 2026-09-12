"""MCP 接入测试：不依赖外部网络，用本地 FastMCP stdio server 验证全链路。"""
import os
import sys
import tempfile

import pytest

from app import agent, config


@pytest.fixture()
def mini_mcp_env(monkeypatch):
    """起一个本地 stdio MCP server（echo 工具）作为假想外部工具。"""
    server_py = os.path.join(tempfile.gettempdir(), "mini_mcp_pytest.py")
    with open(server_py, "w", encoding="utf-8") as f:
        f.write(
            "from mcp.server.fastmcp import FastMCP\n"
            "mcp = FastMCP('mini')\n"
            "@mcp.tool()\n"
            "def echo(text: str) -> str:\n"
            "    '''Echo back the given text.'''\n"
            "    return 'echo:' + text\n"
            "if __name__ == '__main__':\n"
            "    mcp.run()\n"
        )
    monkeypatch.setattr(config, "MCP_SERVERS", {
        "mini": {"command": sys.executable, "args": [server_py]},
    })
    monkeypatch.setattr(agent, "_MCP_TOOLS", None)
    monkeypatch.setattr(agent, "_MCP_RETRY_AT", 0.0)
    yield
    monkeypatch.setattr(agent, "_MCP_TOOLS", None)


def test_normalize_servers_defaults():
    assert agent._normalize_servers({"a": {"command": "x"}}) == {
        "a": {"command": "x", "transport": "stdio"}
    }


def test_disabled_by_default(monkeypatch):
    monkeypatch.setattr(config, "MCP_SERVERS", {})
    assert agent.get_mcp_tools() == []
    assert agent.mcp_status() == {"enabled": False, "servers": [], "tools": []}


def test_load_and_sync_invoke(mini_mcp_env):
    tools = agent.get_mcp_tools()
    assert [t.name for t in tools] == ["echo"]
    # 关键：包装后必须支持同步调用（项目 Agent 走同步 invoke/stream 链路）
    result = tools[0].invoke({"text": "hello"})
    assert "echo:hello" in str(result)


def test_mcp_status_reports_tools(mini_mcp_env):
    agent.get_mcp_tools()
    status = agent.mcp_status()
    assert status["enabled"] is True
    assert status["servers"] == ["mini"]
    assert status["tools"] == ["echo"]


def test_agent_merges_mcp_tools_with_builtin(mini_mcp_env):
    """complete_agent 的工具列表 = 内置工具 + MCP 工具（用假模型探测合并结果）。"""

    class FakeModel:
        def bind_tools(self, tools, **kwargs):
            self.bound = tools
            return self

        def invoke(self, messages, **kwargs):
            from langchain_core.messages import AIMessage

            self.last_messages = messages
            return AIMessage(content="done")

    fake = FakeModel()
    mcp_tools = agent.get_mcp_tools()
    builtin = agent._make_tools(["资料A"], user_id=1)
    prompt = agent.SYSTEM_PROMPT + (agent.MCP_PROMPT_SUFFIX if mcp_tools else "")
    agent_obj = agent.create_agent(fake, builtin + mcp_tools, system_prompt=prompt)
    result = agent_obj.invoke({"messages": [agent.HumanMessage(content="hi")]})

    bound_names = {t.name for t in fake.bound}
    assert "echo" in bound_names                      # MCP 工具已并入
    assert "query_wrong_book" in bound_names          # 内置工具仍在
    assert result["messages"][-1].content == "done"
    # 提示词包含 MCP 指引
    assert "MCP" in prompt


def test_failure_cooldown(monkeypatch):
    """加载失败进入冷却期，冷却内不重复发起子进程拉起。"""
    monkeypatch.setattr(config, "MCP_SERVERS", {"bad": {"command": "no-such-cmd-xyz"}})
    monkeypatch.setattr(agent, "_MCP_TOOLS", None)
    monkeypatch.setattr(agent, "_MCP_RETRY_AT", 0.0)
    assert agent.get_mcp_tools() == []          # 第一次失败
    calls = []

    def _boom():
        calls.append(1)
        raise RuntimeError("should not be called during cooldown")

    import app.agent as a
    monkeypatch.setattr(a, "_load_mcp_tools_async", _boom)
    assert agent.get_mcp_tools() == []          # 冷却期内直接返回缓存 []
    assert calls == []
