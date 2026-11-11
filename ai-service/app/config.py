"""AI 服务配置：从环境变量读取模型提供商参数。"""
import json
import os
from urllib.parse import urlparse

from dotenv import load_dotenv

load_dotenv()

PROVIDER = os.getenv("AI_PROVIDER", "agnes").lower()
API_KEY = os.getenv("AI_API_KEY", "")
BASE_URL = os.getenv("AI_BASE_URL", "https://apihub.agnes-ai.com/v1")
MODEL = os.getenv("AI_MODEL", "agnes-3.0-flash")
HOST = os.getenv("AI_HOST", "0.0.0.0")
PORT = int(os.getenv("AI_PORT", "8000"))

def _search_key(explicit_key: str, model_key: str, model_url: str) -> str:
    """仅生成模型明确直连 DeepSeek 官方 HTTPS 地址时才复用模型 Key。"""
    if explicit_key.strip():
        return explicit_key.strip()
    try:
        parsed = urlparse(model_url)
        if parsed.scheme.lower() == "https" and parsed.hostname == "api.deepseek.com":
            return model_key.strip()
    except ValueError:
        pass
    return ""


DEEPSEEK_API_KEY = _search_key(os.getenv("DEEPSEEK_API_KEY", ""), API_KEY, BASE_URL)
DEEPSEEK_SEARCH_ENABLED = os.getenv("DEEPSEEK_SEARCH_ENABLED", "true").strip().lower() in ("1", "true", "yes")

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

# 答疑会话由本服务管理；沿用原有 MySQL 表，不迁移或复制历史消息。
CHAT_DB_HOST = os.getenv("CHAT_DB_HOST", os.getenv("DB_HOST", "localhost"))
CHAT_DB_PORT = int(os.getenv("CHAT_DB_PORT", os.getenv("DB_PORT", "3306")))
CHAT_DB_NAME = os.getenv("CHAT_DB_NAME", os.getenv("DB_NAME", "learning_assistant"))
CHAT_DB_USER = os.getenv("CHAT_DB_USER", os.getenv("DB_USER", "root"))
CHAT_DB_PASSWORD = os.getenv("CHAT_DB_PASSWORD", os.getenv("DB_PASSWORD", "123456"))
CHAT_AGENT_ENABLED = os.getenv("CHAT_AGENT_ENABLED", "true").lower() in ("1", "true", "yes")

# Agent 写动作工具（排复习提醒 / 学习计划打卡）：默认关闭，只读工具不受影响
AGENT_WRITE_TOOLS = os.getenv("AGENT_WRITE_TOOLS", "false").lower() in ("1", "true", "yes")

# ===== RAG 检索链路（自 Java 侧迁移过来）=====
# 向量库：Chroma 本地持久化目录（默认放在 data/chroma，随 ai-data 卷持久）
CHROMA_DIR = os.getenv(
    "RAG_CHROMA_DIR",
    os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "data", "chroma"))
RAG_COLLECTION = os.getenv("RAG_COLLECTION", "learnassist_bge_m3")

# 向量化：siliconflow（免费 BGE-M3，默认）| hash（离线字符 n-gram）| dashscope（通义）
EMBEDDING_PROVIDER = os.getenv("RAG_EMBEDDING_PROVIDER", "siliconflow").lower()
EMBEDDING_MODEL = (os.getenv("RAG_EMBEDDING_MODEL")
                   or ("text-embedding-v3" if EMBEDDING_PROVIDER == "dashscope" else "BAAI/bge-m3"))
EMBEDDING_BASE_URL = (os.getenv("RAG_EMBEDDING_BASE_URL")
                      or ("https://dashscope.aliyuncs.com/compatible-mode/v1"
                          if EMBEDDING_PROVIDER == "dashscope" else "https://api.siliconflow.cn/v1"))
# 硅基流动使用独立 key；通义保持原有的 AI_API_KEY 复用方式。
EMBEDDING_API_KEY = (os.getenv("RAG_EMBEDDING_API_KEY")
                     or (os.getenv("SILICONFLOW_API_KEY", "") if EMBEDDING_PROVIDER == "siliconflow"
                         else API_KEY))

# 召回与重排参数（原 Java 侧 app.rag.* / ChatService 常量）
RAG_TOP_K = int(os.getenv("RAG_TOP_K", "5"))
RAG_RERANK_CANDIDATES = int(os.getenv("RAG_RERANK_CANDIDATES", "20"))
RAG_REWRITE_ENABLED = os.getenv("RAG_REWRITE_ENABLED", "true").lower() in ("1", "true", "yes")
RAG_RERANK_ENABLED = os.getenv("RAG_RERANK_ENABLED", "true").lower() in ("1", "true", "yes")
# chunk 正文的唯一数据源仍是 Java 侧 t_chunk，索引时按页拉取
RAG_CHUNK_PAGE_SIZE = int(os.getenv("RAG_CHUNK_PAGE_SIZE", "500"))

# ===== 文档解析与分块（自 Java 侧迁移过来）=====
# 定长切块的块大小，与原 Java KbService.CHUNK_SIZE 同值，改动会让新老文档召回粒度不一致
RAG_CHUNK_SIZE = int(os.getenv("RAG_CHUNK_SIZE", "200"))

# ===== MCP（Model Context Protocol）外部工具接入 =====
# 通过 AI_MCP_CONFIG 传入 JSON（格式与 Claude/Cursor 的 mcpServers 一致，transport 缺省为 stdio）。
# 联网搜索已改为直接调用 DeepSeek API；MCP 默认为空，仅承载显式配置的其他工具。
def _parse_mcp_servers(raw: str) -> dict:
    raw = raw.strip()
    if not raw or raw.lower() == "none":
        return {}
    try:
        parsed = json.loads(raw)
        if not isinstance(parsed, dict):
            raise ValueError("必须是 JSON 对象，如 {\"server-name\": {...}}")
        servers = {}
        for name, server in parsed.items():
            args = server.get("args", []) if isinstance(server, dict) else []
            args = args if isinstance(args, list) else []
            # 兼容旧配置：不再启动已经替换的自带 Bing 搜索 server。
            legacy_search = any(
                isinstance(arg, str) and (arg == "mcp_servers.web_search_server"
                                          or arg.replace("\\", "/").endswith("mcp_servers/web_search_server.py"))
                for arg in args
            )
            if legacy_search:
                print(f"[config] 已跳过旧联网搜索 MCP: {name}")
            else:
                servers[name] = server
        return servers
    except (json.JSONDecodeError, ValueError) as e:
        print(f"[config] AI_MCP_CONFIG 解析失败，未启用 MCP: {type(e).__name__}")
        return {}


MCP_SERVERS: dict = _parse_mcp_servers(os.getenv("AI_MCP_CONFIG", ""))

# MCP server 进程启动/加载失败后的重试冷却（秒），避免每个请求都去拉起 npx 子进程
MCP_RETRY_COOLDOWN = int(os.getenv("AI_MCP_RETRY_COOLDOWN", "60"))
