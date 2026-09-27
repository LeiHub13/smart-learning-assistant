# mobile — 移动端（uni-app / Vue3）

独立 uni-app 工程，一套代码编译 **Android / iOS App（还可顺带出 H5）**，直接复用 `backend`(8080) 的全部 REST 接口，后端零改动。

## 目录结构

```
mobile/
├── manifest.json        # uni-app 应用配置（appid 首次运行时由 HBuilderX 生成）
├── pages.json           # 页面注册 + tabBar（答疑 / 练习 / 错题本 / 计划 / 我的）
├── config.js            # ★ 后端地址（唯一需要按环境修改的文件）
├── utils/│   ├── api.js           # uni.request 封装：JWT、{code,message,data} 解包、401 踢回登录、课程缓存
│   ├── sse.js           # ★ 流式 SSE：uni.request enableChunked + 手写 UTF-8 增量解码
│   ├── md.js            # Markdown→HTML，与 Web 端 utils.js 的 mdToHtml 同源同逻辑
│   └── format.js        # fmtTime / parseOptions / parseReview，与 Web 端同源
├── components/
│   ├── mp-html/         # 富文本渲染（Markdown 成品），easycom 自动注册
│   └── line-chart/      # SVG 自绘多系列折线图（App/H5 通用，替代 ECharts）
├── static/
│   ├── app-plus/mp-html/  # mp-html App 端依赖，勿删
│   └── tabbar/            # tabBar 两态图标（PNG，由 scripts/gen-tabbar-icons.mjs 从 SVG 生成）
└── pages/
    ├── login/login.vue      # 登录（可展开修改服务器地址）
    ├── chat/chat.vue        # AI 答疑：流式输出、引用来源、Agent 提议动作确认/取消
    ├── hall/hall.vue          # 对话厅：WebSocket 全员实时群聊、在线名单、断线重连
    ├── practice/practice.vue # 题库练习：出题→作答→AI 批改报告；错题重练/专项/收藏/举一反三入口
    ├── mistake/mistake.vue  # 错题本：红绿选项对比、解析、开始重练、举一反三
    ├── plan/plan.vue        # 学习计划：打卡日历、AI 生成计划、逐日任务打卡
    ├── bank/bank.vue        # 题库浏览：课程/类型/难度/关键词筛选、详情展开、删除（录入/编辑在 Web 端）
    ├── notes/notes.vue      # 学习笔记：新建/编辑/删除，内容 Markdown 渲染
    ├── favorites/favorites.vue # 收藏夹：选项对比、一键重练、取消收藏
    ├── generate/generate.vue   # 讲义生成（流式+存知识库）/ 练习题生成
    ├── courses/courses.vue  # 课程中心：加入/退出公开课程、去练习
    ├── dashboard/dashboard.vue # 学情总览：统计、SVG 成绩曲线、学习热力图、今日推荐
    ├── progress/progress.vue   # 学情分析：掌握度、AI 复习建议、错题归集
    ├── report/report.vue       # 学习报告：AI 周报生成与 Markdown 查看（PDF 导出在 Web 端）
    ├── exam/exam.vue           # 在线考试：随机/手动组卷、限时作答、成绩单
    └── profile/profile.vue  # 我的：功能菜单、改服务器地址、测试连接、退出登录
```

## 如何运行（HBuilderX）

1. HBuilderX → 文件 → 打开目录 → 选本 `mobile` 目录。首次运行若提示生成 appid，确认即可。
2. **配置后端地址**（三选一）：
   - 改 `config.js` 里的 `DEFAULT_BASE_URL`；
   - 或打开 App 在登录页展开「服务器地址」填写；
   - 或登录后在「我的」页修改（存本机，无需重新打包）。
   真机调试：手机与电脑连同一 Wi-Fi，填电脑局域网 IP（cmd 运行 `ipconfig` 看 IPv4），如 `http://192.168.1.5:8080`。
3. 运行 → 运行到浏览器（Chrome）先冒烟；再 运行 → 运行到手机或模拟器（Android 需开 USB 调试）。
4. 打包：发行 → 原生 App-云打包。Android 直接出 apk；**iOS 需要 Apple 开发者账号（$99/年）+ 证书**，Windows 上用云打包即可出 iOS 包。

## 云打包清单

1. **登录**：HBuilderX 需登录 DCloud 账号（已登录 1915878706@qq.com）。若菜单「发行」仍提示未登录，是 cli/GUI 会话不同步，在 HBuilderX 界面里手动点一次即可。
2. **appid**：manifest.json 中 appid 为空属正常，首次「运行到手机」时 HBuilderX 会自动申请并回填。
3. **打包配置**（发行 → 原生 App-云打包）：
   - Android 包名改掉默认 `io.dcloud.*`，如 `com.xxx.learningassistant`；
   - 证书：测试选「公共测试证书」即可，正式包需自有证书；
   - 图标：用 `static/appicon.png`（1024×1024，已生成）自动生成全套图标；
   - Android 权限 INTERNET 已在 manifest.json 声明。
4. **网络**：真机装好后，在 App「我的」页把服务器地址改成电脑局域网 IP 或线上 HTTPS 地址。调试期 HTTP 明文可用；正式包建议后端上 HTTPS（Android 9+/iOS 默认禁明文）。

## 技术要点

- **流式回答**：App 端没有浏览器 `fetch`/`EventSource`，走 `uni.request({ enableChunked: true })` + `onChunkReceived`（要求 HBuilderX ≥ 3.6.5，建议 4.x）。SSE 事件协议与 Web 端 `api.js` 的 `sseStream` 完全一致：`{delta}` 增量、`{sources/actions/saved/done}` 结束。
- **Markdown 渲染**：流式过程中显示纯文本，结束后 `mdToHtml` 转 HTML 交给 `mp-html` 渲染。`utils/md.js` 与 Web 端 `mdToHtml` 是同源实现，改一边记得同步另一边。
- **登录态**：`la_token` 存本地 storage；任何 401 自动清 token 回登录页。当前会话 id（`la_chat_sid`）持久化，重开 App 续聊。
- **Agent 提议动作**：与 Web 端同协议，卡片确认后调 `/api/agent/actions/{id}/confirm` 才真正写库。
- **tab 页跳转传参**：`uni.switchTab` 不能带 query，跨 tab 交接（错题重练/收藏重练/去练习）用本地暂存 `la_practice_pending`，练习页 `onShow` 消费；**必须先初始化课程数据再消费暂存**，否则首次进入时 start 会因无 courseId 静默返回。
- **tab 页数据补拉**：tabBar 页实例化早于登录、且切页瞬间请求可能偶发失败，页面数据在 `onShow` 里判空补拉，`onLoad` 只放一次性守卫。

## 常见问题

- **手机连不上后端**：检查同一 Wi-Fi、电脑防火墙放行 8080、IP 是否变了（DHCP 会换 IP，可在路由器绑定或改用「我的」页现改）。
- **正式包 HTTP 被拦**：Android 9+/iOS 默认禁明文 HTTP。调试期用标准基座没问题；正式打包请勾选允许明文流量，或给后端上 HTTPS（推荐）。
- **iOS 上要 HTTPS**：ATS 强制，正式包必须域名 + 证书。
- **H5 端跨域**：后端 `SecurityWebConfig` 已放开 CORS（`allowedOriginPatterns("*")`），本地 H5 直连即可。
