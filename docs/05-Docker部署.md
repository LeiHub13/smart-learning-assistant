# Docker 一键部署（Ubuntu / 任何支持 Docker 的服务器）

> 相比 `04-Ubuntu部署.md`（手动装环境），本方案把前端/后端/AI 服务打成三个镜像，
> 一条命令构建启动，数据落在持久化卷里，适合演示与生产。

## 一、服务器准备

```bash
# 安装 Docker Engine + Compose 插件
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER && newgrp docker
docker --version && docker compose version
```

## 二、上传工程并配置

```bash
# 本地（Windows 项目根目录）：
#   scp -r backend ai-service frontend docker-compose.yml .env.example deploy.sh \
#       root@<服务器IP>:/opt/learning-assistant/
# 注意：不要上传 backend/data、ai-service/data、ai-service/.env、target、node_modules

cd /opt/learning-assistant
cp .env.example .env
nano .env        # 填 AI_API_KEY，并更换 JWT_SECRET
```

## 三、一键部署

```bash
chmod +x deploy.sh
./deploy.sh
```

脚本会：构建 3 个镜像 → 启动 → 等待 ai-service 与 backend 健康检查 → 打印访问地址。

## 四、容器拓扑

```
docker compose up -d
  ├─ la-web      nginx:80     前端 dist + /api 反代(SSE 关缓冲) → backend
  ├─ la-backend  Java:8080    H2 库在卷 backend-data:/app/data
  └─ la-ai       Python:8000  DeepSeek；记忆在卷 ai-data:/app/data
```

## 五、运维命令

```bash
docker compose ps                  # 状态
docker compose logs -f backend     # 后端日志
docker compose logs -f ai-service  # AI 日志
docker compose down                # 停止（保留数据卷）
docker compose down -v             # 停止并删除数据卷（慎用）
docker compose build backend && docker compose up -d backend   # 改代码后重建单个服务
```

## 六、数据持久化与备份

```bash
# 数据位置（named volume）
docker volume ls | grep learning
# 备份
docker run --rm -v learning-assistant_backend-data:/data -v "$PWD":/backup \
  alpine tar czf /backup/backend-data.tgz -C /data .
docker run --rm -v learning-assistant_ai-data:/data -v "$PWD":/backup \
  alpine tar czf /backup/ai-data.tgz -C /data .
```

## 七、环境变量说明（根目录 .env）

| 变量 | 用途 |
|------|------|
| AI_PROVIDER | deepseek / dashscope / openai-compatible |
| AI_API_KEY | 模型 API Key（必填，未填 deploy.sh 会提示） |
| AI_BASE_URL | 默认 https://api.deepseek.com |
| AI_MODEL | 默认 deepseek-chat |
| JWT_SECRET | 后端 JWT 密钥，生产必须更换 |

> 后端通过 Spring 环境变量覆盖 `application.yml`：`SPRING_APP_MODEL_PYTHON_BASE_URL=http://ai-service:8000`
> 使容器内通过服务名访问 AI 服务，无需改配置文件。

## 八、常见问题

| 现象 | 处理 |
|------|------|
| AI 报"系统繁忙" | `docker compose logs ai-service`；确认 .env 的 AI_API_KEY 正确 |
| 答疑不流式 | 检查 `frontend/nginx.conf` 的 `proxy_buffering off` 是否生效（改后 `docker compose up -d --build frontend`） |
| 端口 80 被占 | 修改 `docker-compose.yml` 的 `frontend.ports: "8088:80"` |
| 想保留旧 H2 数据 | 停止容器，把旧 `learnassist.mv.db` 拷进卷：`docker run --rm -v learning-assistant_backend-data:/data -v "$PWD":/b alpine cp /b/learnassist.mv.db /data/learnassist.mv.db` |
| 构建很慢 | 后端首次拉 Maven 依赖较久属正常；后续走缓存增量构建 |