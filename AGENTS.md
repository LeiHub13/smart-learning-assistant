# 项目说明（Agent 必读）

## 定位

**这是一个 agent 项目（智能学习助手），不是毕设。** 不要以毕设/答辩视角规划功能、写文案或给建议，按普通软件项目对待。

## 架构

- `backend/`：Java 业务骨架（Spring Boot 3 + MyBatis-Plus，8080 端口），默认 MySQL（localhost:3306/learning_assistant，连接参数可用 DB_* 环境变量覆盖；H2 已移除），Redis 为可选 profile（`--spring.profiles.active=redis`，配置见 `application-redis.yml`）
- `ai-service/`：Python AI 服务（FastAPI + langchain + DeepSeek，8000 端口），负责生成/批改/答疑与会话记忆（JSONL 持久化，按 sessionId）
- `frontend/`：Vue3 前端（5173 dev / Nginx 生产）

## 关键约定

- Java ↔ Python 协议：`POST /ai/complete`、`POST /ai/stream`（SSE）、`GET /ai/health`；场景标记见 `PythonAIChatModel`
- LLM provider 仅支持 `python` / `openai-compatible`，无 mock 降级
- 系统无用户角色概念：所有用户权限一致，资源按 userId 隔离；演示账号 pg13/xiaohong/xiaoyu（密码 123456）
- RAG 链路与向量检索在 Java 侧（内存向量库），Python 只做生成与记忆
- 一键部署：根目录 `docker-compose.yml` + `deploy.sh`（三镜像编排，详见 `docs/05-Docker部署.md`）
- push到远程仓库的时候要询问我！！！！

## 验证

- 后端编译：`cd backend && mvn -q compile -DskipTests`
- 前端构建：`cd frontend && npx vite build`
