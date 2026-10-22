#!/usr/bin/env bash
# 在服务器上从固定提交的部署包构建三个应用镜像。
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
  docker compose --env-file "$env_file" -f docker-compose.yml "$@"
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
  sed '/^APP_VERSION=/d' "$env_file" > "${env_file}.tmp"
  printf '\nAPP_VERSION=%s\n' "$version" >> "${env_file}.tmp"
  chmod 600 "${env_file}.tmp"
  mv -f "${env_file}.tmp" "$env_file"
}

check_app_images() {
  local service candidate image
  local -a images
  mapfile -t images < <(compose config --images)
  for service in backend ai-service frontend; do
    image=
    for candidate in "${images[@]}"; do
      if [[ "$candidate" == *-"${service}:${APP_VERSION}" ]]; then
        image="$candidate"
        break
      fi
    done
    [ -n "$image" ] || fail "编排中找不到 ${service}:${APP_VERSION} 镜像"
    docker image inspect "$image" >/dev/null 2>&1 || fail "本机缺少镜像 $image；回滚需要保留上一版镜像"
  done
}

ensure_service_images() {
  local image upstream images
  images=$(compose config --images mysql redis minio) || fail "无法读取基础服务镜像配置"
  while IFS= read -r image; do
    [ -n "$image" ] || continue
    if docker image inspect "$image" >/dev/null 2>&1; then
      continue
    fi
    # 切换镜像源时复用此前已下载的同名同版本 Docker Hub 镜像。
    upstream=
    case "$image" in
      docker.m.daocloud.io/library/*) upstream=${image#docker.m.daocloud.io/library/} ;;
      docker.m.daocloud.io/*) upstream=${image#docker.m.daocloud.io/} ;;
    esac
    if [ -n "$upstream" ] && docker image inspect "$upstream" >/dev/null 2>&1; then
      echo "复用本机镜像 $upstream 为 $image ..."
      docker tag "$upstream" "$image" || fail "无法标记本机镜像 $upstream"
      continue
    fi
    echo "拉取缺少的基础服务镜像 $image ..."
    docker pull "$image" || fail "无法拉取 $image；请确认镜像源可访问且该版本仍然存在"
  done <<< "$images"
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
env_file=$(readlink -f .env) || fail "无法解析 .env"
[ -f "$env_file" ] || fail "缺少 .env 目标文件"
previous_file="$(dirname "$env_file")/.previous.env"
chmod 600 "$env_file"
if grep -Eq '^AI_API_KEY=sk-x{8,}$' "$env_file"; then
  fail "AI_API_KEY 仍是 .env.example 中的模板值"
fi

current=$(read_version "$env_file" || true)
if [ "$1" = rollback ]; then
  target=$(read_version "$previous_file" || true)
  [ -n "$target" ] || fail "没有可回滚的上一个版本"
else
  target="sha-${1#sha-}"
  [[ "$target" =~ ^sha-[0-9a-f]{40}$ ]] || usage
  [ -f RELEASE_SHA ] || fail "缺少 RELEASE_SHA；请解压对应提交的完整部署包"
  bundle_sha=$(tr -d '\r\n' < RELEASE_SHA)
  [ "$bundle_sha" = "${target#sha-}" ] || fail "部署包版本 $bundle_sha 与请求的 ${target#sha-} 不一致"
fi

export APP_VERSION="$target"
compose config --quiet || fail "编排配置无效；请检查 .env 的必填变量"
ensure_service_images

if [ "$target" != "$current" ] && [ "$1" != rollback ]; then
  echo "在服务器构建 $target；构建失败不会切换原有容器"
  df -h .
  compose build backend ai-service frontend || fail "镜像构建失败；原有容器未切换"
else
  echo "使用本机已有镜像 $target"
fi
check_app_images

if ! compose up -d --no-build --pull never || ! wait_ready; then
  if [ -n "$current" ]; then
    echo "尝试恢复上一版本 $current ..." >&2
    export APP_VERSION="$current"
    check_app_images || fail "本机缺少上一版镜像，无法自动恢复"
    if ! compose up -d --no-build --pull never || ! wait_ready; then
      fail "新版本未就绪，自动恢复也失败；请查看 docker compose logs"
    fi
    fail "新版本未就绪，已恢复 $current；请查看 docker compose logs"
  fi
  fail "首次部署未就绪；请查看 docker compose logs"
fi

save_env_version "$target"
if [ -n "$current" ]; then
  save_version "$previous_file" "$current"
fi
echo "部署完成: $target"
echo "前端: http://<服务器 IP>/；查看状态: docker compose ps"
