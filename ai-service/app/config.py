"""AI 服务配置：从环境变量读取模型提供商参数。"""
import json
import os

from dotenv import load_dotenv

load_dotenv()

PROVIDER = os.getenv("AI_PROVIDER", "deepseek").lower()
API_KEY = os.getenv("AI_API_KEY", "")
BASE_URL = os.getenv("AI_BASE_URL", "https://api.deepseek.com")
MODEL = os.getenv("AI_MODEL", "deepseek-chat")
HOST = os.getenv("AI_HOST", "0.0.0.0")
PORT = int(os.getenv("AI_PORT", "8000"))

# 记忆：按 sessionId 持久化的目录
MEMORY_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "data", "memory")
os.makedirs(MEMORY_DIR, exist_ok=True)

# 对话记忆保留轮数
HISTORY_ROUNDS = int(os.getenv("AI_HISTORY_ROUNDS", "5"))

# LLM 并发上限与超时（限流与熔断）
LLM_MAX_CONCURRENCY = os.getenv("AI_MAX_CONCURRENCY", "8")
LLM_TIMEOUT = int(os.getenv("AI_TIMEOUT", "120"))

# Java 骨架内部工具 API 地址（agent 工具回调取数用）
JAVA_TOOL_BASE = os.getenv("JAVA_TOOL_BASE", "http://localhost:8080")
JAVA_TOOL_TOKEN = os.getenv("JAVA_TOOL_TOKEN", "internal-tool-token")

# ===== MCP（Model Context Protocol）外部工具接入 =====
# 通过 AI_MCP_CONFIG 传入 JSON（格式与 Claude/Cursor 的 mcpServers 一致，transport 缺省为 stdio）。
# 例（联网搜索，Tavily 提供 1000 次/月免费额度）：
#   AI_MCP_CONFIG={"web-search":{"command":"npx","args":["-y","tavily-mcp@latest"],"env":{"TAVILY_API_KEY":"xxx"}}}
# 未配置时 Agent 仅使用内置工具，行为零变化；配置后与内置工具合并使用。
MCP_SERVERS: dict = {}
_raw_mcp = os.getenv("AI_MCP_CONFIG", "").strip()
if _raw_mcp:
    try:
        parsed = json.loads(_raw_mcp)
        if not isinstance(parsed, dict):
            raise ValueError("必须是 JSON 对象，如 {\"server-name\": {...}}")
        MCP_SERVERS = parsed
    except (json.JSONDecodeError, ValueError) as e:
        print(f"[config] AI_MCP_CONFIG 解析失败，MCP 工具未启用: {e}")
        MCP_SERVERS = {}

# MCP server 进程启动/加载失败后的重试冷却（秒），避免每个请求都去拉起 npx 子进程
MCP_RETRY_COOLDOWN = int(os.getenv("AI_MCP_RETRY_COOLDOWN", "60"))