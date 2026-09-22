#!/usr/bin/env bash
# 一键部署：构建镜像并启动全部服务
set -euo pipefail
cd "$(dirname "$0")"

# 1. 校验 .env
if [ ! -f .env ]; then
  echo "[1/4] 未找到 .env，正在从 .env.example 创建，请编辑填写 AI_API_KEY"
  cp .env.example .env
  echo "     请先执行: nano .env  填入 AI_API_KEY 后重新运行 $0"
  exit 1
fi
# 模板值/空值提醒：不阻塞部署，但 AI 问答会不可用
# 注：拆成两条简单正则，避免部分 grep 实现（如 ugrep）不支持空交替分支 (a|)
if grep -qE '^AI_API_KEY=sk-x{5,}' .env || grep -qE '^AI_API_KEY=[[:space:]]*$' .env; then
  echo "[1/4] ⚠ 警告：.env 中 AI_API_KEY 仍是模板值或为空，部署后 AI 问答/生成会失败"
fi

# 2. 构建镜像并启动（向量索引由 ai-service 自带 Chroma 持久化，无需额外中间件）
echo "[2/4] 构建镜像并启动服务..."
docker compose up -d --build

# 3. 等待 AI 服务健康（ai-service 未映射宿主机端口，直接查容器 healthcheck）
#    首次部署 Python 依赖导入较慢，最多等 120 秒；容器异常退出则立即失败，不白等
echo "[3/4] 等待 ai-service 健康检查..."
for i in $(seq 1 60); do
  state=$(docker inspect --format='{{.State.Status}}' la-ai 2>/dev/null || echo missing)
  health=$(docker inspect --format='{{.State.Health.Status}}' la-ai 2>/dev/null || echo none)
  if [ "$health" = "healthy" ]; then
    echo "      ai-service 就绪 ✓"
    break
  fi
  if [ "$state" != "running" ] && [ "$state" != "created" ]; then
    echo "      ai-service 容器状态异常（$state），请查看: docker compose logs ai-service"
    exit 1
  fi
  if [ "$i" = 60 ]; then
    echo "      ai-service 未在 120 秒内就绪，请查看: docker compose logs ai-service"
    exit 1
  fi
  sleep 2
done

# 4. 等待后端（本机回环探测；容器异常退出则立即失败）
echo "[4/4] 等待 backend 就绪..."
for i in $(seq 1 60); do
  if curl -sf http://127.0.0.1:8080/api/health >/dev/null 2>&1; then
    echo "      backend 就绪 ✓"
    break
  fi
  state=$(docker inspect --format='{{.State.Status}}' la-backend 2>/dev/null || echo missing)
  if [ "$state" != "running" ] && [ "$state" != "created" ]; then
    echo "      backend 容器状态异常（$state），请查看: docker compose logs backend"
    exit 1
  fi
  if [ "$i" = 60 ]; then
    echo "      backend 未在 120 秒内就绪，请查看: docker compose logs backend"
    exit 1
  fi
  sleep 2
done

echo ""
echo "部署完成："
echo "  前端    http://<服务器IP>      (pg13/123456)"
echo "  后端    http://127.0.0.1:8080  (仅宿主机可访问)"
echo "  AI 服务 容器内网 ai-service:8000（不对外）"
echo "查看日志: docker compose logs -f"
echo "停止:     docker compose down"
