"""AI 服务配置：从环境变量读取模型提供商参数。"""
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