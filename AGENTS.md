# 项目说明（Agent 必读）

## 定位

**这是一个 agent 项目（智能学习助手），不是毕设。** 不要以毕设/答辩视角规划功能、写文案或给建议，按普通软件项目对待。

## 架构

- `backend/`：Java 业务骨架（Spring Boot 3 + MyBatis-Plus，8080 端口），默认 MySQL（localhost:3306/learning_assistant，连接参数可用 DB_* 环境变量覆盖；H2 已移除），Redis 为可选 profile（`--spring.profiles.active=redis`，配置见 `application-redis.yml`）
- `ai-service/`：Python AI 服务（FastAPI + langchain + DeepSeek，8000 端口），负责生成/批改/答疑、会话记忆（JSONL 持久化，按 sessionId）与 **RAG 全链路**（embedding + Chroma 向量索引 + 改写/召回/重排，见 `app/rag.py`、`app/retriever.py`）
- `frontend/`：Vue3 前端（5173 dev / Nginx 生产）

## 关键约定

- Java ↔ Python 协议：`POST /ai/complete`、`POST /ai/stream`（SSE）、`GET /ai/health`；场景标记见 `PythonAIChatModel`
- RAG 检索在 Python 侧：Java 只持有 chunk 正文（`t_chunk` 是唯一数据源），通过 `PythonRagClient` 调 `/ai/index`、`/ai/retrieve` 等触发索引与召回；Python 反向经 `GET /internal/tools/chunks` 拉正文建索引。Java 侧不再有任何向量库实现
- 文档解析与分块在 Python 侧（`app/parsing.py`，端点 `/ai/parse`、`/ai/chunk`）：Java 把上传的原始字节 multipart 转发给 ai-service，拿回 chunks 后落 `t_chunk`。支持 pdf（pypdf）/docx（python-docx）/UTF-8 文本；**旧版 .doc 已不支持**，需另存为 .docx
- 引用来源 `sources`（如 `"1,2,3"`）由 Python 随 SSE done 事件回传，Java 落 `t_chat_message.sources`
- 内部接口鉴权：`X-Internal-Token`（Java `app.internal-tool-token` ↔ Python `JAVA_TOOL_TOKEN`，两侧默认值必须一致）
- LLM provider 仅支持 `python` / `openai-compatible`，无 mock 降级；`openai-compatible`/`spring-ai` 不经过 ai-service，因此没有知识库检索能力
- 系统无用户角色概念：所有用户权限一致，资源按 userId 隔离；演示账号 pg13/xiaohong/xiaoyu（密码 123456）
- 一键部署：根目录 `docker-compose.yml` + `deploy.sh`（三镜像编排，详见 `docs/05-Docker部署.md`）
- push到远程仓库的时候要询问我！！！！

## 验证

- 后端编译：`cd backend && mvn -q compile -DskipTests`
- 前端构建：`cd frontend && npx vite build`
- AI 服务单测：`cd ai-service && python -m pytest tests -q`（`tests/test_mcp.py` 需 `langchain_mcp_adapters`，缺失时的失败与本仓库无关）
