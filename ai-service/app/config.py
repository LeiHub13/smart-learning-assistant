"""AI 服务配置：从环境变量读取模型提供商参数。"""
import json
import os

from dotenv import load_dotenv

load_dotenv()

PROVIDER = os.getenv("AI_PROVIDER", "agnes").lower()
API_KEY = os.getenv("AI_API_KEY", "")
BASE_URL = os.getenv("AI_BASE_URL", "https://apihub.agnes-ai.com/v1")
MODEL = os.getenv("AI_MODEL", "agnes-3.0-flash")
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

# Agent 写动作工具（排复习提醒 / 学习计划打卡）：默认关闭，只读工具不受影响
AGENT_WRITE_TOOLS = os.getenv("AGENT_WRITE_TOOLS", "false").lower() in ("1", "true", "yes")

# ===== RAG 检索链路（自 Java 侧迁移过来）=====
# 向量库：Chroma 本地持久化目录（默认放在 data/chroma，随 ai-data 卷持久）
CHROMA_DIR = os.getenv(
    "RAG_CHROMA_DIR",
    os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "data", "chroma"))
RAG_COLLECTION = os.getenv("RAG_COLLECTION", "learnassist_chunks")

# 向量化：hash（离线字符 n-gram，128 维，默认）| dashscope（通义 text-embedding-v3）
EMBEDDING_PROVIDER = os.getenv("RAG_EMBEDDING_PROVIDER", "hash").lower()
EMBEDDING_MODEL = os.getenv("RAG_EMBEDDING_MODEL", "text-embedding-v3")
EMBEDDING_BASE_URL = os.getenv(
    "RAG_EMBEDDING_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1")
# 不单独配置时复用 AI_API_KEY（通义场景下与生成模型同一把 key）
EMBEDDING_API_KEY = os.getenv("RAG_EMBEDDING_API_KEY", API_KEY)

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
# 未配置时默认启用自带 web-search（Bing 中文，免 key 零依赖）；设 AI_MCP_CONFIG=none 显式关闭；
# 或传自定义 JSON 替换，如社区 tavily-mcp（质量更好，需 key）：
#   AI_MCP_CONFIG={"web-search":{"command":"npx","args":["-y","tavily-mcp@latest"],"env":{"TAVILY_API_KEY":"xxx"}}}
DEFAULT_MCP_SERVERS = {
    "web-search": {"command": "python", "args": ["-m", "mcp_servers.web_search_server"]}
}
MCP_SERVERS: dict = {}
_raw_mcp = os.getenv("AI_MCP_CONFIG", "").strip()
if _raw_mcp.lower() == "none":
    MCP_SERVERS = {}
elif _raw_mcp:
    try:
        parsed = json.loads(_raw_mcp)
        if not isinstance(parsed, dict):
            raise ValueError("必须是 JSON 对象，如 {\"server-name\": {...}}")
        MCP_SERVERS = parsed
    except (json.JSONDecodeError, ValueError) as e:
        print(f"[config] AI_MCP_CONFIG 解析失败，回退自带 web-search: {e}")
        MCP_SERVERS = dict(DEFAULT_MCP_SERVERS)
else:
    MCP_SERVERS = dict(DEFAULT_MCP_SERVERS)

# MCP server 进程启动/加载失败后的重试冷却（秒），避免每个请求都去拉起 npx 子进程
MCP_RETRY_COOLDOWN = int(os.getenv("AI_MCP_RETRY_COOLDOWN", "60"))