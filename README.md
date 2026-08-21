# 基于大语言模型的智能学习助手系统 · V3（Java 骨架 + Python AI 服务）

毕设系统架构：**Spring Boot 单模块骨架 + Python langchain AI 服务**。Java 负责业务与数据（用户/课程/知识库 RAG 检索/会话/练习/学情），AI 能力（答疑生成、讲义/题目生成、主观题批改、学情建议）由独立 Python 服务（`ai-service/`，FastAPI + langchain + DeepSeek）承载；Java 通过 `PythonAIChatModel` 适配器调用，SSE 逐块透传，前端无感知。基础设施（缓存/向量/存储/消息队列）全部**可降级**。

> 架构设计全文见 `docs/02-系统架构设计.md`；AI 服务接口约定见 `ai-service/README.md`。

## 一、运行环境

| 依赖 | 版本 |
|------|------|
| JDK | 17+ |
| Maven | 3.8+ |
| Python | 3.10+（开发环境 3.13） |
| DeepSeek API Key | 在线大模型 |

数据库默认 H2 文件库（免安装），首次启动自动建表并写入演示数据。中间件（Redis/Milvus/MinIO/RabbitMQ）**全部可选**，默认内存降级。

## 二、启动

```bash
# 1. 启动 Python AI 服务（默认 8000 端口）
cd ai-service
pip install -r requirements.txt
copy .env.example .env   # 填入 AI_API_KEY
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000

# 2. 启动 Java 骨架（默认 8080 端口）
cd backend
mvn package -DskipTests
java -jar target/learning-assistant-1.0.0.jar
```

访问 **http://localhost:8080**（健康检查：`GET /api/health`，免登录；AI 服务健康检查：`GET http://localhost:8000/ai/health`）。

### Docker 一键部署（可选）

```bash
cp .env.example .env      # 填入 AI_API_KEY
./deploy.sh               # 构建 3 个镜像并启动：前端(80) / 后端(8080) / AI服务(8000)
```

详细步骤见 `docs/05-Docker部署.md`；传统手动部署见 `docs/04-Ubuntu部署.md`。

## 三、演示账号（密码均为 123456）

| 账号 | 角色 |
|------|------|
| `student` | 学生 |
| `teacher` | 教师 |
| `admin` | 管理员 |

登录：`POST /api/auth/login {"username":"student","password":"123456"}` → 返回 `accessToken` / `refreshToken`（JWT），后续请求头带 `Authorization: Bearer <accessToken>`。

## 四、已验证接口（全功能阶段）

| 接口 | 说明 |
|------|------|
| GET /api/health | 健康检查 + 各中间件当前启用实现（免登录） |
| POST /api/auth/login、/register | JWT 双 Token 认证 |
| GET /api/auth/me | 当前用户 |
| GET /api/courses | 课程列表（含 enrolled / kbCount / questionCount） |
| POST /api/courses | 创建课程（教师） |
| POST /api/courses/{id}/enroll | 选课 |
| GET/POST /api/courses/{courseId}/kb | 知识库列表 / 创建 |
| GET/POST /api/kb/{kbId}/documents | 文档列表 / 文本文档同步分块索引 |
| DELETE /api/kb/{kbId}/documents/{docId} | 删除文档（含向量） |
| GET/POST /api/chat/sessions | 会话列表 / 创建 |
| GET /api/chat/sessions/{id}/messages | 会话消息 |
| POST /api/chat/sessions/{id}/stream | SSE 流式答疑（RAG 检索 + 引用 sources + 历史记忆） |
| POST /api/generate/lecture | 生成讲义（Markdown，入库） |
| POST /api/generate/questions | AI 出题（解析入库 source=AI） |
| GET /api/generate/history | 生成历史 |
| GET /api/practice/paper?courseId&count | 随机抽题 |
| POST /api/practice/submit | 提交判分（客观规则 + 主观 LLM 批改 + 掌握度更新） |
| GET /api/practice/history | 练习记录 |
| GET /api/practice/{id} | 练习报告（明细 + 题目 + AI 点评） |
| GET /api/progress/summary?courseId | 学情汇总（掌握度 / 平均 / 错题本 / AI 建议） |
| GET /api/analytics/kp-stats?courseId=1 | 知识点掌握度聚合（**XML Mapper 复杂查询示例**） |
| GET /api/analytics/practice-overview?courseId=1 | 学生练习联表统计（XML Mapper 示例） |

接口文档：`/swagger-ui.html`（springdoc）。

## 五、配置切换（backend/src/main/resources/application.yml）

```yaml
app:
  model:
    provider: python        # python(Python langchain ai-service) | openai-compatible
    python-base-url: http://localhost:8000
    api-key: ""             # openai-compatible 时填写
  infra:
    cache-mode: memory      # memory | redis
    vector-mode: memory     # memory | milvus
    storage-mode: local     # local | minio
    mq-mode: memory         # memory | rabbit
```

数据库：默认 H2，切换 MySQL 见 `application.yml` 中 datasource 注释。

## 六、工程结构（backend/ 单模块）

```
backend/
└── src/main/java/com/example/learningassistant/
    ├── common/         通用：统一响应/异常/全局异常处理/租户上下文
    ├── security/       认证授权：JWT 双 Token、拦截器、CORS
    ├── infra/          基础设施：缓存/向量库/对象存储/消息队列（可降级）
    ├── ai/             AI 平台：模型适配器(python/openai-compatible)、场景 Prompt、向量化
    ├── user/           用户/认证
    ├── course/         课程
    ├── kb/             知识库与 RAG（文档登记、异步索引、向量检索）
    ├── chat/           答疑（流式链路）
    ├── generate/       AI 内容生成
    ├── practice/       题库练习与 AI 批改
    ├── progress/       学情分析
    ├── exam/           考试中心（骨架）
    ├── assignment/     作业中心（骨架）
    ├── analytics/      统计分析（XML Mapper 复杂查询示例）
    ├── recommend/      个性化推荐（骨架）
    ├── notify/         通知中心（骨架）
    └── web/            接入层：Controller 聚合、OpenAPI
（启动类 LearningAssistantApplication 位于根包；配置/建表脚本/演示数据在 src/main/resources）
```

AI 服务（ai-service/）：

```
ai-service/
├── requirements.txt         # Python 依赖
├── .env                     # 模型提供商配置（API Key）
└── app/
    ├── main.py              # FastAPI 入口：/ai/health、/ai/complete、/ai/stream(SSE)
    ├── config.py            # 环境变量配置
    ├── memory.py            # 会话记忆（JSONL 持久化，按 sessionId）
    └── chains.py            # langchain 场景链：rag_qa/free/lecture/questions/review/advice
```

Java 与 Python 的分工：Java 负责向量检索（VectorStore + chunk 表）、会话/消息/题目落库、判分与掌握度聚合；Python 负责大模型生成与对话记忆（按 sessionId 持久化，支持多轮指代）。场景标记（RAG_QA/GEN_LECTURE 等）由 `PythonAIChatModel` 解析后映射为 Python 场景。

## 七、Mapper 策略（重要设计）

- **简单单表 CRUD** → `XxxMapper extends BaseMapper<Entity>` 通用方法（如 `UserMapper`）；
- **复杂查询（聚合/联表/统计）** → 显式 Mapper 接口 + XML，SQL 落在各模块 `resources/mapper/*.xml`（如 `la-analytics` 的 `AnalyticsMapper.xml`，可审查、可调优）；
- 加载：`@MapperScan("com.example.learningassistant.*.mapper")` + `mybatis-plus.mapper-locations: classpath*:mapper/**/*.xml`；
- 返回类型用 DTO（驼峰自动映射），不使用 Map（避免列名大小写不一致）。

## 八、文档索引

- `docs/01-需求分析与方案设计.md`（V1 需求文档）
- `docs/02-系统架构设计.md`（V2 架构设计：总体架构/模块/数据库/核心链路/部署/演进路线）
