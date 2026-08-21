# Ubuntu 部署指南

部署拓扑：

```
外网客户端
   │ https/http :80
   ▼
Nginx（静态托管前端 dist + /api 反向代理，SSE 关闭缓冲）
   │ /api/* → http://127.0.0.1:8080
   ▼
Java 后端（8080，H2 文件库 backend/data）
   │ /ai/* → http://127.0.0.1:8000
   ▼
Python ai-service（8000，langchain + DeepSeek）
   │ OpenAI 协议
   ▼
DeepSeek API
```

## 一、环境准备（Ubuntu 20.04/22.04/24.04）

```bash
sudo apt update
# JDK 17
sudo apt install -y openjdk-17-jdk
# Maven（用于构建后端，也可本机构建后只传 jar）
sudo apt install -y maven
# Python 3.10+
sudo apt install -y python3 python3-pip python3-venv
# Nginx
sudo apt install -y nginx
# Node 20+（构建前端，构建完可卸载）
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt install -y nodejs
```

## 二、上传工程

```bash
sudo mkdir -p /opt/learning-assistant && sudo chown $USER /opt/learning-assistant
# 在 Windows 本地（项目根目录）执行，排除构建产物与敏感文件：
#   scp -r backend frontend ai-service docs root@<服务器IP>:/opt/learning-assistant/
# 或在服务器上 git clone 后手动拷贝 .env 与 data
```

上传后确认以下文件存在：

```bash
cd /opt/learning-assistant
ls backend/src/main/resources/application.yml
ls ai-service/app/main.py
ls frontend/package.json
```

## 三、后端（Java，8080）

```bash
cd /opt/learning-assistant/backend
mvn package -DskipTests            # 产物 target/learning-assistant-1.0.0.jar
mkdir -p data                      # H2 文件库目录（首次启动自动建表+种子数据）
```

> 若本地已运行过，可把 Windows 上的 `backend/data/learnassist.mv.db` 一起传上来，
> 这样保留已有用户/课程/知识库/会话数据；否则会自动重新初始化演示数据。

## 四、AI 服务（Python，8000）

```bash
cd /opt/learning-assistant/ai-service
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
nano .env        # 填入 AI_API_KEY（DeepSeek 等），默认已指向 https://api.deepseek.com
```

## 五、前端（构建 + Nginx）

```bash
cd /opt/learning-assistant/frontend
npm install
npm run build                     # 产物 dist/
sudo mkdir -p /var/www/learning-assistant
sudo cp -r dist/* /var/www/learning-assistant/
```

Nginx 配置 `/etc/nginx/sites-available/learning-assistant`：

```nginx
server {
    listen 80;
    server_name _;                 # 或你的域名

    root /var/www/learning-assistant;
    index index.html;

    # 前端路由（History 模式回退到 index.html）
    location / {
        try_files $uri $uri/ /index.html;
    }

    # 后端 API：含 SSE 流式，必须关闭缓冲
    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_read_timeout 300s;
        proxy_buffering off;
        proxy_cache off;
        chunked_transfer_encoding on;
    }
}
```

```bash
sudo ln -s /etc/nginx/sites-available/learning-assistant /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
```

## 六、systemd 托管服务（开机自启 / 崩溃自动拉起）

`/etc/systemd/system/ai-service.service`：

```ini
[Unit]
Description=AI Service (langchain)
After=network.target

[Service]
WorkingDirectory=/opt/learning-assistant/ai-service
ExecStart=/opt/learning-assistant/ai-service/venv/bin/python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
```

`/etc/systemd/system/learning-assistant.service`：

```ini
[Unit]
Description=Learning Assistant Backend
After=network.target ai-service.service

[Service]
WorkingDirectory=/opt/learning-assistant/backend
ExecStart=/usr/bin/java -jar /opt/learning-assistant/backend/target/learning-assistant-1.0.0.jar
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

启用并启动：

```bash
sudo systemctl daemon-reload
sudo systemctl enable ai-service learning-assistant
sudo systemctl start ai-service
sudo systemctl start learning-assistant
```

## 七、验证

```bash
# 三个组件健康检查
curl http://127.0.0.1:8000/ai/health          # Python 正常
curl http://127.0.0.1:8080/api/health          # Java 正常
curl -I http://127.0.0.1/                      # Nginx 正常

# 后端日志确认模型路由
sudo journalctl -u learning-assistant -f | grep "LLM 提供方"

# 浏览器打开 http://<服务器IP> → student/123456 登录 → 答疑提问，
# 观察回答是否逐字流式出现（验证 SSE 透传）。
```

## 八、常见问题

| 现象 | 原因与解决 |
|------|-----------|
| 答疑不流式、一次性返回 | Nginx 未关缓冲：检查 `proxy_buffering off`，或去掉 `X-Accel-Buffering` 干扰 |
| AI 报"系统繁忙" | Python 未启动/Key 未填：`curl /ai/health`；确认后端 `app.model.python-base-url` 指向 8000 |
| 8080 被占 | `sudo ss -ltnp | grep 8080` 查占用；改 `application.yml` 端口 |
| H2 数据库锁 / AUTO_SERVER | 只允许一个 Java 进程访问 `backend/data/learnassist`；升级/备份先 `sudo systemctl stop learning-assistant` |
| 前端登录后 404 | 前端未用 History 回退：确认 `location / { try_files ... /index.html; }` |
| 端口外网不通 | 云厂商安全组 / 防火墙放行 80（及调试用 8000/8080）：`sudo ufw allow 80/tcp` |

## 九、数据备份

```bash
sudo systemctl stop learning-assistant
sudo tar czf backup-$(date +%F).tgz \
  /opt/learning-assistant/backend/data \        # H2 数据库
  /opt/learning-assistant/backend/data/files \  # 上传的文档
  /opt/learning-assistant/ai-service/data/memory # 会话记忆
sudo systemctl start learning-assistant
```

> 注意：`.env`（AI API Key）与数据库含敏感数据，切勿提交到 Git/公开仓库。