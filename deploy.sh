#!/usr/bin/env bash
# 在服务器上部署 CI 发布的三个应用镜像。服务器无需项目源码或 Dockerfile。
set -euo pipefail
umask 077
cd "$(dirname "$0")"

usage() {
  echo "用法: $0 <40 位 Git 提交 SHA | sha-<40 位 SHA> | rollback>" >&2
  exit 2
}

fail() {
  echo "部署失败: $*" >&2
  exit 1
}

compose() {
  docker compose -f docker-compose.yml "$@"
}

read_version() {
  local file="$1" value
  [ -f "$file" ] || return 1
  value=$(sed -n 's/^APP_VERSION=//p' "$file" | head -n 1)
  [[ "$value" =~ ^sha-[0-9a-f]{40}$ ]] || return 1
  printf '%s' "$value"
}

save_version() {
  local file="$1" version="$2"
  printf 'APP_VERSION=%s\n' "$version" > "${file}.tmp"
  chmod 600 "${file}.tmp"
  mv -f "${file}.tmp" "$file"
}

save_env_version() {
  local version="$1"
  sed '/^APP_VERSION=/d' .env > .env.tmp
  printf '\nAPP_VERSION=%s\n' "$version" >> .env.tmp
  chmod 600 .env.tmp
  mv -f .env.tmp .env
}

wait_for_ai() {
  local i status health
  for i in $(seq 1 90); do
    status=$(docker inspect --format='{{.State.Status}}' la-ai 2>/dev/null || true)
    health=$(docker inspect --format='{{.State.Health.Status}}' la-ai 2>/dev/null || true)
    [ "$health" = healthy ] && return 0
    if [ "$status" != running ] && [ "$status" != created ]; then
      echo "ai-service 状态异常: ${status:-missing}" >&2
      return 1
    fi
    sleep 2
  done
  echo "ai-service 在 180 秒内未通过健康检查" >&2
  return 1
}

wait_for_http() {
  local url="$1" name="$2" container="$3" i status
  for i in $(seq 1 90); do
    if curl --fail --silent --max-time 3 "$url" >/dev/null; then
      return 0
    fi
    status=$(docker inspect --format='{{.State.Status}}' "$container" 2>/dev/null || true)
    if [ "$status" != running ] && [ "$status" != created ]; then
      echo "$name 状态异常: ${status:-missing}" >&2
      return 1
    fi
    sleep 2
  done
  echo "$name 在 180 秒内未就绪" >&2
  return 1
}

wait_ready() {
  echo "等待 AI、后端和前端就绪..."
  wait_for_ai || return 1
  wait_for_http http://127.0.0.1:8080/api/health backend la-backend || return 1
  wait_for_http http://127.0.0.1/api/health frontend la-web || return 1
}

[ "$#" -eq 1 ] || usage
[ -f .env ] || fail "缺少 .env；请从 .env.example 创建，并填写私有配置"
chmod 600 .env
if grep -Eq '^AI_API_KEY=sk-x{8,}$' .env; then
  fail "AI_API_KEY 仍是 .env.example 中的模板值"
fi

current=$(read_version .env || true)
if [ "$1" = rollback ]; then
  target=$(read_version .previous.env || true)
  [ -n "$target" ] || fail "没有可回滚的上一个版本"
else
  target="sha-${1#sha-}"
  [[ "$target" =~ ^sha-[0-9a-f]{40}$ ]] || usage
fi

if [ "$target" = "$current" ]; then
  echo "当前已是 $target"
  exit 0
fi

echo "部署 $target"
export APP_VERSION="$target"
compose config --quiet || fail "编排配置无效；请检查 .env 的必填变量"
compose pull backend ai-service frontend || fail "镜像拉取失败；原有容器未切换"

if ! compose up -d --no-build || ! wait_ready; then
  if [ -n "$current" ]; then
    echo "尝试恢复上一版本 $current ..." >&2
    export APP_VERSION="$current"
    if ! compose up -d --no-build || ! wait_ready; then
      fail "新版本未就绪，自动恢复也失败；请查看 docker compose logs"
    fi
    fail "新版本未就绪，已恢复 $current；请查看 docker compose logs"
  fi
  fail "首次部署未就绪；请查看 docker compose logs"
fi

save_env_version "$target"
if [ -n "$current" ]; then
  save_version .previous.env "$current"
fi
echo "部署完成: $target"
echo "前端: http://<服务器 IP>/；查看状态: docker compose ps"
