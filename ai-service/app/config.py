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