# ai-service：基于 langchain 的 AI 能力服务

Java 骨架（backend）通过 HTTP 调用的独立 AI 模块：FastAPI + langchain + 在线大模型（DeepSeek/通义等，兼容 OpenAI 协议）。

## 启动

```bash
pip install -r requirements.txt
copy .env.example .env      # 填写 AI_API_KEY
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

健康检查：`GET http://localhost:8000/ai/health`

## 接口

| 接口 | 说明 |
|------|------|
| GET /ai/health | 健康检查 + 模型配置摘要 |
| POST /ai/complete | 非流式生成 `{scene, question, chunks?, sessionId?}` → `{content}` |
| POST /ai/stream | 流式生成（SSE）`{scene, question, chunks?, sessionId?}` → `data:{"delta":"..."}` … `data:{"done":true}` |
| GET /ai/history/{sessionId} | 查看会话记忆 |

## 场景（scene）

| 场景 | 用途 | 模型输出 |
|------|------|----------|
| agent | **ReAct Agent**：模型自主决定是否检索资料（工具循环） | Markdown 文本（引用 [n]） |
| rag_qa | 知识库答疑（RAG，引用 [n]） | Markdown 文本 |
| free | 自由对话 | 文本 |
| lecture | 生成讲义 | Markdown |
| questions | 生成练习题 | JSON 数组（type/stem/options/answer/analysis/kpName） |
| review | 主观题批改 | JSON（{"score":0-10,"comment"}） |
| advice | 学情复习建议 | 分条文本 |

## Agent 模式（scene=agent）

`app/agent.py` 基于 `langchain.agents.create_agent` 实现 ReAct 循环：

- **工具**：`list_material_topics`（资料清单）/ `search_materials`（关键词检索），
  通过闭包绑定 Java 端随请求传入的 chunks（向量检索 Top-K 结果）；
- **循环**：思考 → 调工具 → 观察 → … → 回答，`recursion_limit=20` 防打转；
- **接入方式**：Java 端 `app.model.agent-enabled=true` 时，RAG_QA / FREE 自动升级为
  agent 场景（`PythonAIChatModel` 内完成映射），接口与前端零改动；
- **记忆**：与 rag_qa/free 共用 sessionId 维度的 JSONL 会话记忆。

## 记忆

`agent` / `rag_qa` / `free` 场景按 `sessionId` 将对话写入 `data/memory/*.jsonl`，生成时拼入最近 5 轮历史，支持多轮指代。

## 配置（.env）

| 变量 | 默认                     | 说明 |
|------|--------------------------|------|
| AI_PROVIDER | deepseek                 | deepseek / dashscope / openai-compatible |
| AI_API_KEY | -                        | 必填 |
| AI_BASE_URL | https://api.deepseek.com | 通义：https://dashscope.aliyuncs.com/compatible-mode/v1 |
| AI_MODEL | deepseek-v4-flash        | 通义示例：qwen-plus |
| AI_PORT | 8000                     | 服务端口（Java 端 `app.model.python-base-url` 需一致） |
