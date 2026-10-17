# 智能学习助手

基于大语言模型的个人学习助手：课程管理、知识库 RAG 答疑、AI 内容生成、题库练习、在线考试、学情分析与学习报告，另有跨用户实时「对话厅」与 uni-app 移动端。

## 架构

```
frontend/    Vue3 + Vite（dev 5173 / 生产 Nginx 托管）
backend/     Spring Boot 3 + MyBatis-Plus（8080）：业务、落库、判分、JWT 鉴权
ai-service/  FastAPI + langchain + DeepSeek（8000）：生成/批改/答疑、记忆、RAG 全链路
mobile/      uni-app 移动端（对话 / 练习 / 报告等，后端地址在 config.js 配置）
```

- Java ↔ Python 协议：`POST /ai/complete`、`POST /ai/stream`（SSE 逐块透传）、`GET /ai/health`；内部接口走 `X-Internal-Token` 鉴权
- RAG 全链路在 Python 侧（embedding + Chroma 向量索引 + 改写/召回/重排）；正文唯一数据源是 MySQL `t_chunk`，Java 经 `PythonRagClient` 触发索引与召回
- 文档解析与分块在 Python（pdf / docx / UTF-8 文本），chunks 落 `t_chunk`
- 实时通信：答疑为 SSE 流式；对话厅为 WebSocket（`/ws/hall`）广播
- 基础设施：MySQL 必选，Redis / MinIO 可选（Docker 编排内置；Redis 也可用 `--spring.profiles.active=redis` 本地启用）

## 功能

💬 智能答疑（RAG + 引用 + 会话记忆） · 🏠 对话厅（跨用户实时消息流） · ✨ 流式讲义 / 自适应出题 · 📝 题库练习（统一判分 + 成绩曲线） · 📋 在线考试（组卷 / 限时 / 成绩单） · 📈 学情分析（掌握度 / 错题本 / 时长热力图） · 🗓️ 计划打卡 · 📄 周报 PDF · 📒 学习笔记 · ⭐ 收藏重练 · 🔍 全局搜索 · 🔔 通知（站内信 + 邮件） · 🌙 深色模式 · 📱 移动端

## 快速开始

**生产部署**

CI 从提交编译后端 JAR 和前端 `dist/`，连同 AI 服务源码、三个运行镜像的 Dockerfile 和部署文件打成 `deployment-package.tar.gz`，包内有 `RELEASE_SHA`，不含 `.git`、`.env`、后端和前端源码或本地依赖目录。将部署包上传到服务器，服务器把 JAR 和 `dist/` 装进后端、前端运行镜像，并按源码构建 AI 服务镜像后启动容器；无需在服务器维护 Git 仓库，也无需安装 JDK、Maven 或 Node。上传、配置、磁盘空间和回滚步骤见 [DEPLOYMENT.md](DEPLOYMENT.md)。

```bash
sha=$(tar -xOzf deployment-package.tar.gz RELEASE_SHA)
[[ "$sha" =~ ^[0-9a-f]{40}$ ]] || exit 1
release_dir="/opt/learning-assistant/releases/$sha"
mkdir -p "$release_dir"
tar -xzf deployment-package.tar.gz -C "$release_dir"
test -f /opt/learning-assistant/.env || cp "$release_dir/.env.example" /opt/learning-assistant/.env
# 首次部署时先编辑 /opt/learning-assistant/.env，填写私有密钥
chmod 600 /opt/learning-assistant/.env
test -e "$release_dir/.env" || ln -s /opt/learning-assistant/.env "$release_dir/.env"
cd "$release_dir"
./deploy.sh "$sha"
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
数据库默认 `localhost:3306/learning_assistant`（连接参数可用 `DB_*` 环境变量覆盖），首次启动自动建表；演示数据需显式启用。

## 演示账号

仅显式设置 `APP_DEMO_SEED_ENABLED=true`（Docker 为 `.env` 的 `DEMO_SEED_ENABLED=true`）时创建 `pg13`、`xiaozhang`、`xiaodi` 等固定密码测试账号。生产默认关闭自动播种；已有数据库中的账号不受影响。系统无角色概念，资源按 userId 隔离；接口文档为 `/swagger-ui.html`。


