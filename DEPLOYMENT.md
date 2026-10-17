# 生产部署：服务器本地构建

CI 将一个 Git 提交的 `backend/`、`ai-service/`、`frontend/` 构建目录与 `docker-compose.yml`、`deploy.sh`、配置示例和本指南打成 `deployment-package.tar.gz`。包内的 `RELEASE_SHA` 记录完整提交 SHA；不包含 `.git`、私有 `.env`、`node_modules`、`.venv`、运行数据或本地构建产物。服务器上传此包后本地构建三个应用镜像，不需要用 Git 同步整个仓库，也不需要单独上传 JAR 或前端 `dist/`。

服务器需要 Docker Engine、Docker Compose 插件和足够的磁盘空间。JDK、Maven、Python、Node 在 Docker 构建阶段使用，宿主机无需安装。构建仍需下载 Maven、JRE、Python、Node、Nginx 等基础镜像，以及 Maven、PyPI、npm 依赖；MySQL、Redis、MinIO 容器所用镜像也需存在或下载。基础镜像通常来自 Docker Hub，应用镜像不从 GHCR 拉取。

## 准备发布包

可从 CI 的部署产物下载 `deployment-package.tar.gz`。尚未推送时，也可在本机把修改提交后运行 `python scripts/package_deploy.py deployment-package.tar.gz --ref HEAD`。两种方式均从已提交版本生成发布包；不要手工把工作区、`.git`、`.env` 或依赖目录打包上传。每个 SHA 使用独立的空目录，避免旧版已删除的源码文件留在下一次构建上下文中。

在本机上传发布包：

~~~bash
scp deployment-package.tar.gz root@115.29.230.205:/tmp/
ssh root@115.29.230.205
~~~

在服务器上读取包内 SHA 并解压。`/opt/learning-assistant/.env` 是服务器私有配置；每个版本目录的 `.env` 链接到这一个文件，升级时不要用模板覆盖它：

~~~bash
set -e
sha=$(tar -xOzf /tmp/deployment-package.tar.gz RELEASE_SHA)
[[ "$sha" =~ ^[0-9a-f]{40}$ ]] || { echo 'RELEASE_SHA 无效' >&2; exit 1; }
release_dir="/opt/learning-assistant/releases/$sha"
test ! -e "$release_dir" || { echo "版本目录已存在：$release_dir；请先核对后重试" >&2; exit 1; }
mkdir -p "$release_dir"
tar -xzf /tmp/deployment-package.tar.gz -C "$release_dir"
test "$(cat "$release_dir/RELEASE_SHA")" = "$sha"

if [ ! -f /opt/learning-assistant/.env ]; then
  cp "$release_dir/.env.example" /opt/learning-assistant/.env
  echo '请先填写 /opt/learning-assistant/.env 中的私有配置' >&2
fi
chmod 600 /opt/learning-assistant/.env
if [ ! -L "$release_dir/.env" ]; then
  test ! -e "$release_dir/.env"
  ln -s /opt/learning-assistant/.env "$release_dir/.env"
fi
cd "$release_dir"
~~~

首次部署时，编辑 `/opt/learning-assistant/.env`，至少填写 `AI_API_KEY`、`JWT_SECRET`、`MYSQL_ROOT_PASSWORD`、`MINIO_ACCESS_KEY`、`MINIO_SECRET_KEY` 和 `INTERNAL_TOOL_TOKEN`。`INTERNAL_TOOL_TOKEN` 必须在 Java 与 AI 服务间一致，编排文件从同一个变量注入。新库注册和找回密码需要配置 `MAIL_HOST`、`MAIL_USERNAME`、`MAIL_PASSWORD` 等 SMTP 参数；`MAIL_ENABLED` 只控制通知邮件。若旧库已有账号，保留原 MySQL、MinIO 凭据，不能只改 `.env` 就变更已初始化数据库的密码。`.env` 和 SMTP 授权码不能进入发布包或 Git；旧仓库历史中出现过的 SMTP 授权码应撤销重发。

## 构建并启动

先查看当前剩余空间与 Docker 占用；三个应用的多阶段构建需要额外空间：

~~~bash
df -h / /var/lib/docker
docker system df
~~~

如空间不足，先扩容或核对后清理无用的构建缓存。同机还有其他项目，清理时不要删除其他项目运行中的镜像和容器；不要执行 `docker compose down -v` 或 `docker system prune --volumes`，它们可能删除 MySQL、MinIO 等持久数据卷。

确认 `.env` 已填写后，在新解压的版本目录运行；不要使用 `/opt/learning-assistant/` 根目录中先前上传的旧版 `deploy.sh`：

~~~bash
chmod +x deploy.sh
./deploy.sh "$sha"
docker compose ps
~~~

脚本验证参数与 `RELEASE_SHA` 一致，在服务器构建三个应用镜像，构建成功后用 `docker compose up -d --no-build --pull never` 切换容器，并检查 AI、后端、前端健康。成功后将 `APP_VERSION=sha-<完整 SHA>` 写入服务器私有 `.env`。构建失败时不应切换现有容器；首次安装则等待镜像构建和基础设施启动。

镜像名默认仍使用 `ghcr.io/leihub13/smart-learning-assistant-{backend,ai-service,frontend}:sha-<完整 SHA>` 格式，**这里仅作为本机镜像标签和回滚标识**。本地构建不会向 GHCR 推送镜像，部署脚本也不会从 GHCR 拉取这三个应用镜像；无需 `docker login ghcr.io`。可以用 `.env` 的 `IMAGE_PREFIX` 改本机镜像名前缀，但同一服务器升级和回滚应保持一致。

更新 `.env` 中的运行配置而不更换源码版本时，可以在当前版本目录运行 `docker compose up -d --no-build --pull never` 使容器重新创建。全新数据库默认不创建固定密码演示账号。需要管理员时，先注册普通账号，再把用户名加到 `.env` 的 `ADMIN_USERNAMES`，然后用 `docker compose up -d --no-build --pull never backend` 重建后端容器。

## 升级、备份与回滚

每个新版本重复上传和解压流程，放在 `/opt/learning-assistant/releases/<新 SHA>/`，继续共用 `/opt/learning-assistant/.env`。Compose 工程名固定为 `learning-assistant`，因此正常更新复用 `mysql-data`、`redis-data`、`minio-data`、`backend-data`、`ai-data` 五个命名卷。升级前应备份数据库及需要保留的文件、对象和 AI 数据；镜像回滚不会恢复数据库结构或数据。

进入当前使用的部署包版本目录后执行：

~~~bash
docker compose ps
docker compose logs --tail 100 backend ai-service frontend
./deploy.sh rollback
~~~

每次成功升级把上一个版本写入服务器私有 `.previous.env`。`rollback` 只使用服务器本地保留的旧版应用镜像；清理旧镜像前先确认不再需要回滚。部署脚本在新版本健康检查失败时也会尝试恢复上一版。版本目录保留各版源码、编排文件和 `RELEASE_SHA`，方便核对与重建；私有 `.env`、`.previous.env` 和备份放在版本目录外，不随发布包更换。
