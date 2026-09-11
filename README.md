# 智能学习助手

基于大语言模型的个人学习助手：课程管理、知识库 RAG 答疑、AI 内容生成、题库练习、在线考试、学情分析与学习报告。

**架构**：Spring Boot 3 单体（业务/数据/判分）+ Python 服务（FastAPI + langchain + DeepSeek，生成/记忆）；SSE 逐块透传；缓存/向量库/对象存储/消息队列四类基础设施可降级。

## 功能

💬 智能答疑（RAG + 引用 + 记忆，可升级 Agent） · ✨ 流式讲义/自适应出题 · 📝 题库练习（统一判分 + 成绩曲线） · 📋 在线考试（组卷/限时/成绩单） · 📈 学情分析（掌握度/错题本/建议缓存/时长热力图） · 🗓️ 计划打卡 · 📄 周报 PDF · 📒 学习笔记 · ⭐ 收藏重练 · 🔍 全局搜索 · 🔔 通知（站内信 + 邮件）

## 快速开始

**Docker 一键部署（推荐）**

```bash
cp .env.example .env      # 填入 AI_API_KEY（DeepSeek）
./deploy.sh               # 前端(80) / 后端(8080) / AI服务(8000) / MySQL / Redis / MinIO
```

**本地手动启动**

```bash
# 1. Python AI 服务（8000 端口）
cd ai-service && pip install -r requirements.txt
copy .env.example .env && python -m uvicorn app.main:app --host 0.0.0.0 --port 8000

# 2. Java 后端（8080 端口，需本地 MySQL）
cd backend && mvn package -DskipTests
java -jar target/learning-assistant-1.0.0.jar
```

环境：JDK 17+ / Maven 3.8+ / Python 3.10+ / MySQL 8。
数据库默认 `localhost:3306/learning_assistant`（root/1234，`DB_*` 环境变量可覆盖），首次启动自动建表并写入演示数据。

## 演示账号（密码均 `123456`）

`xiaoming` · `xiaohong` · `xiaoyu`（权限一致、资源按 userId 隔离；接口文档 `/swagger-ui.html`）

## 配置切换（application.yml）

```yaml
app:
  model:
    provider: python        # python | openai-compatible | spring-ai
  embedding:
    provider: hash          # hash（离线） | dashscope
  infra:
    cache-mode: memory      # memory | redis
    vector-mode: memory     # memory | milvus
    storage-mode: local     # local | minio
    mq-mode: memory         # memory | rabbit
```

Docker 下以上项由 compose 环境变量注入（Redis/MinIO 编排内置，Milvus 走 `--profile milvus`）。

## 工程结构

```
backend/     Spring Boot 3 单模块（MyBatis-Plus）
  ├── ai/kb/chat/generate/    模型适配、知识库 RAG、流式答疑、内容生成
  ├── practice/exam/          练习与考试（GradingService 统一判分 + 掌握度联动）
  ├── progress/study/search/  学情、时长统计、全局搜索
  ├── plan/report/notes/favorite/  计划/报告/笔记/收藏
  ├── infra/                  缓存/向量库/对象存储/消息队列（可降级）
  └── security/               JWT 双 Token
ai-service/  FastAPI + langchain（场景链 / Agent / JSONL 会话记忆）
frontend/    Vue3 + Vite（Nginx 托管）
```

**分工**：Java 负责向量检索、业务落库、判分与掌握度；Python 负责大模型生成与会话记忆。场景标记由 `PythonAIChatModel` 映射。

## 文档

`docs/02-系统架构设计.md`（架构）· `docs/05-Docker部署.md`（部署）· `docs/07-测试文档.md`（测试）
