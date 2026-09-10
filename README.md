# 智能学习助手（Java + Python AI 双服务架构）

基于大语言模型的个人学习助手：课程管理、知识库 RAG 检索、流式 AI 答疑、AI 内容生成、题库练习、在线考试、学情分析与学习报告。

**架构**：Spring Boot 3 单体负责业务与数据，独立 Python 服务（FastAPI + langchain + DeepSeek）负责大模型生成与对话记忆；Java 通过 SSE 逐块透传，基础设施（缓存/向量库/对象存储/消息队列）全部可降级切换。

## 功能一览

- 💬 **智能答疑**：RAG 向量检索 + 引用标注 + 会话记忆，可升级 ReAct Agent
- ✨ **AI 内容生成**：流式讲义、自适应出题（按掌握度调难度）
- 📝 **题库练习**：随机抽题、客观题规则判分、主观题 AI 批改、成绩曲线
- 📋 **在线考试**：手动/随机组卷、限时作答、统一判分、成绩单
- 📈 **学情分析**：知识掌握度、错题本、AI 复习建议（缓存化）、学习时长热力图
- 🗓️ **学习计划**：AI 生成每日任务 + 打卡
- 📄 **学习报告**：AI 周报 + PDF 导出
- 👤 **个人中心**：头像（MinIO）、昵称、密码、通知邮箱
- 🔔 **通知中心**：站内信 + 邮件双渠道，艾宾浩斯间隔重复提醒

## 快速开始

### Docker 一键部署（推荐）

```bash
cp .env.example .env        # 填入 AI_API_KEY（DeepSeek）
./deploy.sh                 # 前端(80) / 后端(8080) / AI服务(8000) / MySQL / Redis / MinIO
```

### 本地手动启动

```bash
# 1. Python AI 服务（8000 端口）
cd ai-service
pip install -r requirements.txt
copy .env.example .env      # 填入 AI_API_KEY
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000

# 2. Java 后端（8080 端口，需本地 MySQL）
cd backend
mvn package -DskipTests
java -jar target/learning-assistant-1.0.0.jar
```

**运行环境**：JDK 17+、Maven 3.8+、Python 3.10+、MySQL 8。
**数据库**：默认 `localhost:3306/learning_assistant`（root/1234），环境变量 `DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD` 可覆盖；首次启动自动建表并写入演示数据。

## 演示账号（密码均为 `123456`）

| 账号 | 昵称 |
|------|------|
| `xiaoming` | 小明 |
| `xiaohong` | 小红 |
| `xiaoyu` | 小宇 |

所有用户权限一致（无角色区分），资源按 userId 隔离。接口文档：`/swagger-ui.html`（springdoc）。

## 配置切换（application.yml）

```yaml
app:
  model:
    provider: python        # python | openai-compatible（通义/DeepSeek/智谱等 OpenAI 协议）
    agent-enabled: true     # 答疑升级 ReAct Agent
  embedding:
    provider: hash          # hash（离线） | dashscope（通义向量模型）
  rag:
    rewrite-enabled: true   # 多轮对话查询改写
    rerank-enabled: true    # LLM 精排
  infra:
    cache-mode: memory      # memory | redis（fail-fast）
    vector-mode: memory     # memory | milvus
    storage-mode: local     # local | minio
    mq-mode: memory         # memory | rabbit
```

Docker 环境下以上项均由 `docker-compose.yml` 环境变量注入（Redis/MinIO 编排内置，Milvus 走 `--profile milvus`）。

## 工程结构

```
backend/     Spring Boot 3 单模块（MyBatis-Plus）
  ├── ai/        模型适配器(python/openai-compatible)、向量化
  ├── kb/        知识库与 RAG（分块索引、向量检索）
  ├── chat/      流式答疑（SSE）
  ├── generate/  AI 内容生成
  ├── practice/  题库练习（GradingService 统一判分）
  ├── exam/      在线考试
  ├── progress/  学情分析 + 间隔重复提醒
  ├── study/     学习时长统计
  ├── plan/report/notify/   计划/报告/通知
  ├── infra/     缓存/向量库/对象存储/消息队列（可降级）
  └── security/  JWT 双 Token 认证
ai-service/  FastAPI + langchain
  ├── main.py     /ai/health、/ai/complete、/ai/stream(SSE)
  ├── chains.py   场景链（rag_qa/lecture/questions/review/advice/plan/report…）
  ├── agent.py    ReAct Agent（回调 Java 内部工具 API）
  └── memory.py   会话记忆（JSONL 持久化 + 滚动摘要压缩）
frontend/    Vue3 + Vite（Nginx 托管）
```

**分工**：Java 负责向量检索、业务落库、判分与掌握度聚合；Python 负责大模型生成与会话记忆（按 sessionId 持久化，支持多轮）。场景标记（RAG_QA/GEN_LECTURE 等）由 Java 侧 `PythonAIChatModel` 映射为 Python 场景。

## 文档

- `docs/02-系统架构设计.md` — 总体架构/模块/数据库/核心链路
- `docs/05-Docker部署.md` / `docs/04-Ubuntu部署.md` — 部署
- `docs/07-测试文档.md` — 测试
