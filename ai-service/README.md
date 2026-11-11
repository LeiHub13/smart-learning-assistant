# ai-service：基于 langchain 的 AI 能力服务

FastAPI + LangChain AI 服务：负责答疑会话与消息、Agent、RAG，以及供 Java 调用的生成与批改能力。在线模型使用 OpenAI 兼容协议。

## 启动

```bash
pip install -r requirements.txt
copy .env.example .env      # 填写 AI_API_KEY
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

健康检查：`GET http://localhost:8000/ai/health`

启动前准备 MySQL。答疑服务使用 `CHAT_DB_HOST`、`CHAT_DB_PORT`、`CHAT_DB_NAME`、
`CHAT_DB_USER`、`CHAT_DB_PASSWORD` 连接数据库；未设置时依次使用对应的 `DB_*` 和本地默认值。
Java 和 Python 必须指向同一个数据库。Python 启动时幂等创建/升级答疑表，沿用历史
`t_chat_session`、`t_chat_message` 与原有 ID，不复制或删除历史消息。

知识库向量默认使用硅基流动的免费 `BAAI/bge-m3` API。申请硅基流动 API Key 后，
在 `ai-service/.env` 设置 `RAG_EMBEDDING_API_KEY`；Docker 部署则在根目录 `.env`
设置 `SILICONFLOW_API_KEY`。模型默认使用独立的 Chroma 集合 `learnassist_bge_m3`，
从旧模型切换后调用 `POST /api/kb/reindex` 全量重建索引。未配置向量 API Key 时，
知识库索引和召回会报错；生成模型的 `AI_API_KEY` 不会被当作硅基流动 Key 使用。

## 接口

| 接口 | 说明 |
|------|------|
| `/api/chat/sessions/**` | 答疑会话、消息与 SSE；Bearer JWT 交给 Java 验证后再校验会话归属 |
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

- **工具**：资料检索走 Python RAG；错题、掌握度、笔记等业务工具回调 Java，历史会话工具读取 Python 自己管理的消息表；
- **循环**：思考 → 调工具 → 观察 → … → 回答，`recursion_limit=20` 防打转；
- **接入方式**：答疑默认启用 Agent；设置 `CHAT_AGENT_ENABLED=false` 可使用普通 RAG / 自由对话链路；
- **记忆**：与 rag_qa/free 共用 sessionId 维度的 JSONL 会话记忆。

### 联网搜索

Agent 使用 DeepSeek 官方 Search API，而不是原来的 Bing 网页搜索 MCP。将 DeepSeek 平台的 Key 写入 `DEEPSEEK_API_KEY`；如果生成模型本身直连 `https://api.deepseek.com`，也可留空并复用 `AI_API_KEY`。使用 Agnes 等其他模型时必须单独填写 DeepSeek Key；没有配置或调用失败时，工具会如实报告失败，不会自动切换搜索源。设置 `DEEPSEEK_SEARCH_ENABLED=false` 可关闭搜索。

`GET /ai/health` 的 `webSearch.enabled` 和 `webSearch.configured` 只反映开关与 Key 是否配置，不会发起搜索，也不能证明 Key 有效或余额充足。搜索结果携带网页链接，与知识库来源编号分开。Search 按成功且有结果的请求计费，具体价格以 [DeepSeek 搜索与抓取文档](https://api-docs.deepseek.com/zh-cn/guides/search/)为准。

其他 MCP 工具仍可通过 `AI_MCP_CONFIG` 接入；留空或 `none` 均不启用 MCP，且不影响 DeepSeek 搜索。旧配置中指向 `mcp_servers.web_search_server` 的项目会被跳过；自定义 MCP 若也提供名为 `web_search` 的工具，DeepSeek 搜索启用时优先使用 DeepSeek。

## 记忆

`agent` / `rag_qa` / `free` 场景按 `sessionId` 将对话写入 `data/memory/*.jsonl`，生成时拼入最近 5 轮历史，支持多轮指代。
Python 删除会话时清理对应记忆。课程删除先由 Java 在事务中记录事件，再由 Python 清理课程会话；失败会自动重试。
答疑轮次状态记录在 `t_chat_turn`：回答成功落库后才幂等追加会话记忆并由 Java 完成次数结算；记忆同步、结算或退款失败时后台重试。同一会话在上一轮记忆同步完成前不开始新一轮；进程异常退出的运行中轮次在心跳超时后进入退款流程。

## 配置（.env）

| 变量 | 默认                     | 说明 |
|------|--------------------------|------|
| AI_PROVIDER | agnes                    | agnes / deepseek / dashscope 等（仅健康检查展示用） |
| AI_API_KEY | -                        | 必填 |
| AI_BASE_URL | https://apihub.agnes-ai.com/v1 | DeepSeek：https://api.deepseek.com；通义：https://dashscope.aliyuncs.com/compatible-mode/v1 |
| AI_MODEL | agnes-3.0-flash          | DeepSeek 示例：deepseek-chat；通义示例：qwen-plus |
| DEEPSEEK_API_KEY | -                 | 官方搜索 Key；模型直连 DeepSeek 官方地址时可复用 AI_API_KEY |
| DEEPSEEK_SEARCH_ENABLED | true        | 是否向 Agent 提供 DeepSeek 联网搜索 |
| AI_MCP_CONFIG | 空                    | 其他 MCP server 的 JSON 配置；none 关闭 MCP |
| AI_PORT | 8000                     | 服务端口（Java 端 `app.model.python-base-url` 需一致） |
| CHAT_DB_HOST / CHAT_DB_PORT / CHAT_DB_NAME | localhost / 3306 / learning_assistant | 答疑会话数据库，必须与 Java 指向同一库 |
| CHAT_DB_USER / CHAT_DB_PASSWORD | root / 123456 | 数据库凭据；生产使用私有环境变量 |
| CHAT_AGENT_ENABLED | true | 是否让答疑使用 Agent 模式 |

## 测试

常规测试运行 `python -m pytest tests -q`。额外的真实数据库测试仅在显式设置
`CHAT_TEST_MYSQL=1` 且 `CHAT_DB_NAME=chat_test`、数据库地址为本机时运行。
跨服务联调需另设 `CHAT_TEST_LIVE=1`，并在本机 18123 / 18000 端口启动使用独立
`chat_live_test` 库的 Java / Python 测试服务。旧表升级测试需 `CHAT_TEST_UPGRADE=1`，
只连接本机 13306 端口的隔离 MySQL，创建并删除随机命名的临时测试库。
