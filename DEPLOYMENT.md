# 生产部署：镜像发布与服务器迁移

生产服务器只运行 Docker 镜像和私有配置。三个应用镜像由 GitHub Actions 构建并发布到 GHCR，服务器不需要 Git 仓库、源码、JDK、Node 或 Python 构建环境。

## 发布一个版本

1. 将待发布提交推送到 `main` 或当前使用的 `agent` 分支。两条分支的 push 都会发布镜像；工作流进入默认分支后，也可以在 GitHub Actions 的 **Build and Publish Docker Images** 中选择已推送的分支手动运行。PR 只验证构建，不发布镜像。
2. 等工作流成功。它发布三个镜像：`ghcr.io/leihub13/smart-learning-assistant-{backend,ai-service,frontend}:sha-<完整提交 SHA>`，并上传 `learning-assistant-deploy-sha-<SHA>` artifact。artifact 内的 `deployment-package.tar.gz` 只含 `docker-compose.yml`、`deploy.sh`、`.env.example`、`README.md` 和本指南；不含源码或 `.env`。
3. 将部署包传到服务器，解压到独立目录，例如 `/opt/learning-assistant`。保留服务器自己的 `.env`。镜像包若为私有，需要先在服务器使用具有该包 `read:packages` 权限的 GitHub token 执行 `docker login ghcr.io`；token 不写进 `.env` 或仓库。
4. 执行 `chmod +x deploy.sh && ./deploy.sh <完整提交 SHA>`。脚本先拉镜像，再用 `docker compose up -d --no-build` 启动，检查 AI、后端、前端健康后把已部署版本写入 `.env`。更新配置后重新运行同一版本可用 `docker compose up -d --no-build` 重建容器。

镜像前缀默认是 `ghcr.io/leihub13/smart-learning-assistant`；若将镜像同步到其他仓库，在服务器 `.env` 设置 `IMAGE_PREFIX`。镜像 tag 固定为 `sha-<完整 SHA>`。服务器目录不要放 Dockerfile 或应用源码。

全新数据库默认不创建固定密码演示账号。注册与找回密码通过邮件验证码完成，因此新安装必须在 `.env` 配置可用的 `MAIL_HOST`、`MAIL_USERNAME`、`MAIL_PASSWORD` 等 SMTP 参数；`MAIL_ENABLED` 仅控制通知邮件，不控制验证码发送。默认保留管理员用户名 `admin`，公开注册不能占用管理员白名单中的名字。需要管理员时，先注册一个普通账号，再把它的用户名写入 `.env` 的 `ADMIN_USERNAMES`，执行 `docker compose up -d --no-build backend` 使配置生效。已有数据库的账号在迁移时保留；如果旧库还有固定密码演示管理员，迁移后应通过邮箱验证码重置其密码。

## 从现有源码部署迁移

当前服务器的旧项目目录为 `/root/smart-learning-assistant`，Compose 工程名为 `learning-assistant`。迁移后的编排保留这个工程名和五个命名卷：`mysql-data`、`redis-data`、`minio-data`、`backend-data`、`ai-data`。因此正常切换会复用现有数据库、对象、文件、AI 记忆和向量索引。不要执行 `docker compose down -v`。

服务器还运行其他项目；以下命令只针对学习助手。先在本机下载 Actions artifact，取出其中的 `deployment-package.tar.gz`，然后在本机执行：

```bash
scp deployment-package.tar.gz root@115.29.230.205:/tmp/
ssh root@115.29.230.205
```

在服务器上准备新目录，并复制原有配置。旧目录以及它的 `.env.example` 均无需修改：

```bash
mkdir -p /opt/learning-assistant
tar -xzf /tmp/deployment-package.tar.gz -C /opt/learning-assistant
cp /root/smart-learning-assistant/.env /opt/learning-assistant/.env
cd /opt/learning-assistant
chmod 600 .env
printf '\nINTERNAL_TOOL_TOKEN=%s\n' "$(openssl rand -hex 32)" >> .env
chmod +x deploy.sh
```

旧 `.env` 没有 `INTERNAL_TOOL_TOKEN`；新增时必须同一值同时交给后端和 AI 服务，新编排已完成这两个注入。请核对 `.env` 中已有的 `AI_API_KEY`、`JWT_SECRET`、`MYSQL_ROOT_PASSWORD`、`MINIO_ACCESS_KEY`、`MINIO_SECRET_KEY` 仍有效，保留原数据库和 MinIO 凭据，不能只改 `.env` 就更改已初始化数据库的密码。邮件凭据也只留在服务器 `.env`。`APP_VERSION` 首次成功部署后由脚本写入，不必手填。

先确认磁盘空间，再预拉镜像，避免服务停止后才等待下载：

```bash
df -h /
APP_VERSION=sha-<完整提交 SHA> docker compose pull backend ai-service frontend
```

服务器原有可用空间约 5.9 GB，Docker 构建缓存占约 18.5 GB。若拉取因空间不足失败，应先检查 `docker system df`；清理可回收构建缓存会让同机其他项目的未来构建变慢，但不会删除运行容器或数据卷。迁移前用 `docker compose images` 确认旧应用镜像仍在，清理空间时不要删除它们。不要使用 `docker system prune --volumes`。

切换前先拉取备份工具镜像，并确认五个旧卷都存在；否则不要停旧服务。停机后给五个卷做一致的文件备份。下面的 `set -e` 让归档或校验失败时立即停止执行，不能在备份失败后继续部署。备份目录权限只给当前用户：

```bash
set -euo pipefail
docker pull busybox
for name in mysql redis minio backend ai; do
  docker volume inspect "learning-assistant_${name}-data" >/dev/null || exit 1
done
cd /root/smart-learning-assistant
docker compose images
docker compose stop
umask 077
mkdir -p /root/la-backup
for name in mysql redis minio backend ai; do
  docker run --rm \
    -v "learning-assistant_${name}-data:/data:ro" \
    -v /root/la-backup:/backup \
    busybox tar -czf "/backup/${name}-data.tgz" -C /data .
  test -s "/root/la-backup/${name}-data.tgz"
  gzip -t "/root/la-backup/${name}-data.tgz"
done
cd /opt/learning-assistant
./deploy.sh <完整提交 SHA>
```

如果备份失败，先排查原因或在旧目录执行 `docker compose up -d --no-build` 恢复服务，不要继续切换。第一次切换失败时，旧目录与本地构建的旧镜像还在；可以回到旧目录执行 `docker compose up -d --no-build` 恢复旧版本。若数据库在新后端启动时发生过结构变更，旧应用的兼容性需检查，必要时用切换前备份恢复数据卷。

## 日常运维与回滚

```bash
cd /opt/learning-assistant
docker compose ps
docker compose logs -f --tail 100 backend ai-service frontend
./deploy.sh <另一个已发布的完整 SHA>
./deploy.sh rollback
```

每次成功升级都会把上一个镜像版本记在 `.previous.env`，`rollback` 使用该版本。新版本健康检查失败时脚本会尝试自动恢复上一版；首次迁移尚无上一版记录，因此保留旧部署目录直到新版本验证通过。`rollback` 只切换镜像版本，仍使用当前目录的编排文件；若编排、环境变量或数据结构也改变，应保留每版部署包，并按对应版本恢复。回滚应用镜像不会自动回滚数据库结构或数据。

GHCR 的 `sha-<提交 SHA>` 是可被重新发布覆盖的标签。发布后应保存对应的 Actions 部署包和已拉取镜像；工作流重跑时需确认标签是否改变。需要更严格的内容锁定时，应把三个镜像固定到各自的 digest。

`.env`、`.previous.env`、备份和 GHCR 登录凭据都属于服务器私有配置，不进入 Git 或 Actions artifact。新版本稳定并确认备份可用后，再清理旧源码目录；不要同时删除旧镜像或数据卷。旧仓库内曾包含 SMTP 授权码；应在邮件服务商处撤销并重发授权码，再只通过服务器 `.env` 配置。
