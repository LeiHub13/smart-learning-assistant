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

# 2. 构建镜像并启动（.env 中 VECTOR_MODE=milvus 时附带启用 milvus 容器组）
COMPOSE_ARGS=(-d --build)
if grep -qE '^VECTOR_MODE=milvus' .env; then
  echo "[2/4] 检测到 VECTOR_MODE=milvus，附加 milvus profile..."
  COMPOSE_ARGS=(--profile milvus -d --build)
fi
echo "[2/4] 构建镜像并启动服务..."
docker compose up "${COMPOSE_ARGS[@]}"

# 3. 等待 AI 服务健康（ai-service 未映射宿主机端口，直接查容器 healthcheck）
echo "[3/4] 等待 ai-service 健康检查..."
for i in $(seq 1 30); do
  status=$(docker inspect --format='{{.State.Health.Status}}' la-ai 2>/dev/null || echo "missing")
  if [ "$status" = "healthy" ]; then
    echo "      ai-service 就绪 ✓"
    break
  fi
  [ "$i" = 30 ] && echo "      ai-service 未就绪，请查看 docker compose logs ai-service" && exit 1
  sleep 2
done

# 4. 等待后端
echo "[4/4] 等待 backend 就绪..."
for i in $(seq 1 30); do
  if curl -sf http://127.0.0.1:8080/api/health >/dev/null 2>&1; then
    echo "      backend 就绪 ✓"
    break
  fi
  [ "$i" = 30 ] && echo "      backend 未就绪，请查看 docker compose logs backend" && exit 1
  sleep 2
done

echo ""
echo "部署完成："
echo "  前端    http://<服务器IP>      (xiaoming/123456)"
echo "  后端    http://127.0.0.1:8080  (仅宿主机可访问)"
echo "  AI 服务 容器内网 ai-service:8000（不对外）"
echo "查看日志: docker compose logs -f"
echo "停止:     docker compose down"