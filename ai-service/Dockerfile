# AI 服务：FastAPI + langchain
FROM python:3.12-slim
WORKDIR /app
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1

# nodejs：MCP stdio 服务（如联网搜索 tavily-mcp）通过 npx 拉起时需要；不启用 MCP 可删
RUN apt-get update \
    && apt-get install -y --no-install-recommends nodejs npm \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app
# 自研 MCP server（联网搜索等，AI_MCP_CONFIG 引用）
COPY mcp_servers ./mcp_servers
# 会话记忆（挂载卷）
RUN mkdir -p /app/data/memory
VOLUME /app/data

EXPOSE 8000
CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]