# Web 功能 → Android 实现对照与验收清单

> 本表是原生 Android 客户端（`mobile/android`）的**验收依据**：每一项都对应 Web 端一个真实交互，
> 状态含义见下方「状态图例」；接口与字段以仓库现有后端为准，未新增服务端接口。
> 接口路径与后端 `backend/src/main/java/com/example/learningassistant/**` 保持一致，未新增服务端接口。
>
> 约定：
> - 认证 `Authorization: Bearer <JWT>`；响应统一 `{code, message, data}`，业务失败是 **HTTP 200 + code≠0**，登录失效是 **HTTP 401 + code=401**。
> - AI 生成走 **POST SSE**；额度不足等预检失败以 `application/json` 信封返回，客户端按 Content-Type 分支。
> - 对话厅走 **WebSocket `/ws/hall?token=`**；心跳 `{"type":"ping"}`，重连 3s/6s/…最多 5 次后手动。
> - 次数包是**人工核对**模式：客户端只提交付款信息，绝不做自动到账、自动支付或写死价格。

状态图例：

- `已实现（真机验证）`：在模拟器（API 37）上连接真实后端跑通，见第 12 节验证记录。
- `已实现`：UI + 接口 + 加载/空/错误态齐全，`assembleDebug`、JVM 单测与组件 UI 测试全部通过，但该条交互未逐项真机走查。
- `已实现（核心层）`：能力在网络/数据层完成并被上层页面复用，单测覆盖。
- `不适用`：按设计不该由客户端承担（例如服务端人工核对接口）。

## 1. 账号与个人设置

| Web 页面/操作 | Android 页面与操作 | 接口 | 状态 |
| --- | --- | --- | --- |
| 登录（用户名密码） | 登录页「登录」Tab | `POST /api/auth/login` | 已实现（真机验证） |
| 注册（邮箱+验证码+昵称） | 登录页「注册」Tab | `POST /api/auth/email-code`(scene=register)、`POST /api/auth/register` | 已实现 |
| 忘记密码（邮箱验证码重置） | 登录页「重置密码」表单 | `POST /api/auth/email-code`(scene=reset_password)、`POST /api/auth/reset-password` | 已实现 |
| 演示账号一键填充（仅 demo 播种开启时存在） | 登录页账号芯片 | 无接口（本地填充） | 已实现 |
| 登录状态恢复（免重复登录） | 冷启动直进首页；token 按服务器分片存于 EncryptedSharedPreferences | `GET /api/auth/me` | 已实现（真机验证） |
| 退出登录（后端无登出接口） | 抽屉「退出登录」，清当前服务器分片并停止心跳/通知/WS | 无接口 | 已实现 |
| 未登录介绍页 | 欢迎页（CTA 跳登录） | 无接口 | 已实现 |
| 个人资料：昵称 | 个人中心「昵称」保存 | `PUT /api/auth/me/profile` | 已实现（真机验证） |
| 用户名修改（admin 账号禁改） | 个人中心「用户名」 | `PUT /api/auth/me/username` | 已实现 |
| 邮箱绑定/更换 | 个人中心「通知邮箱」 | `PUT /api/auth/me/email` | 已实现 |
| 头像上传（jpg/png/webp，≤2MB） | 个人中心「更换头像」→ 系统图片选择器 | `POST /api/auth/me/avatar`（multipart `file`） | 已实现 |
| 密码修改（原密码方式） | 个人中心「修改密码」 | `PUT /api/auth/me/password` | 已实现 |
| 密码修改（邮箱验证码方式） | 个人中心「用验证码改密」 | `POST /api/auth/email-code` + `POST /api/auth/reset-password` | 已实现 |
| 通知偏好（8 类邮件开关，反选语义） | 个人中心「邮件通知」多选 | `PUT /api/auth/me/notify-pref` | 已实现 |
| 数据导出：练习记录 xlsx | 个人中心「导出练习记录」→ 下载/分享 | `GET /api/export/practice.xlsx` | 已实现（真机验证） |
| 数据导出：考试成绩 xlsx | 个人中心「导出考试成绩」 | `GET /api/export/exams.xlsx` | 已实现 |
| 深色模式 | 全局主题（跟随系统/浅色/深色），色板对齐 Web CSS 变量 | 无接口 | 已实现（真机验证） |
| 后端地址配置（模拟器/局域网/线上） | 服务器设置页 + 登录页可展开；仅调试包允许 HTTP | `GET /api/health` 探活 | 已实现 |

## 2. 首页看板

| Web | Android | 接口 | 状态 |
| --- | --- | --- | --- |
| 学习总览（累计分钟、活跃天数、连续天数） | 首页统计卡 | `GET /api/study/summary` | 已实现（真机验证） |
| 学习热力图（84 天，5 档配色） | 首页热力图（原生 Canvas，可点按看单日） | `GET /api/study/summary` | 已实现 |
| 成绩趋势多系列折线（按课程，图例可隐藏） | 首页折线图（Canvas + 点按读数） | `GET /api/practice/trend?courseId=`（每课程一次） | 已实现 |
| 答错数/收藏数/闪卡到期数/练习次数徽标 | 首页指标行 | `/api/mistakes?size=1`、`/api/favorites/count`、`/api/flashcards/due`、`/api/practice/history?size=1` | 已实现 |
| 今日推荐（startup/review_kp/practice_kp/document，可跳练/展开片段） | 首页推荐卡，点击按 `action` 翻译路由跳转 | `GET /api/recommend?courseId=` | 已实现 |
| 快捷入口（练习/错题/收藏/闪卡/考试/答疑/学情） | 首页快捷入口 + 底部导航 | 无接口 | 已实现（真机验证） |
| 学习时长心跳（前台每 60 秒） | `StudyHeartbeat`，仅前台且已登录 | `POST /api/study/heartbeat` | 已实现（真机验证） |

## 3. 智能答疑（ChatView）

| Web | Android | 接口 | 状态 |
| --- | --- | --- | --- |
| 会话列表 / 切换 | 答疑页顶部抽屉式会话列表 | `GET /api/chat/sessions` | 已实现（真机验证） |
| 新建会话（自由/知识库两种） | 「新对话」「知识库答疑」按钮 | `POST /api/chat/sessions` | 已实现 |
| 重命名 / 删除会话 | 会话行菜单（输入对话框 / 二次确认） | `PUT /api/chat/sessions/{id}`、`DELETE /api/chat/sessions/{id}` | 已实现 |
| 历史消息加载 | 进入会话即全量加载 | `GET /api/chat/sessions/{id}/messages` | 已实现（真机验证） |
| 流式回答（逐字上屏） | POST SSE + 生成中气泡光标 | `POST /api/chat/sessions/{id}/stream` | 已实现（真机验证） |
| 生成中切换会话再切回 | 流只写入归属会话，切回时接回进行中的气泡 | 同上 | 已实现（核心层） |
| 断流/后台生成 | 提示「连接中断，请稍后查看生成结果」，不退款不重发 | 同上 | 已实现（核心层） |
| Markdown 渲染（标题/列表/代码/表格/引用） | `MarkdownText` 原生渲染 | — | 已实现 |
| 引用编号 [1][2] 点击看原文片段 | 角标可点，展开来源面板 | `GET /api/kb/chunks?ids=`（≤10 条） | 已实现 |
| 推荐追问（继续问，点击即发问） | 答案下方芯片，点按直接提问 | SSE `followups` | 已实现（真机验证） |
| 苏格拉底模式 | 会话级开关（与费曼互斥） | `PUT /api/chat/sessions/{id}` {socratic} | 已实现 |
| 费曼模式 | 会话级开关 | `PUT` {feynman, socratic=false} | 已实现 |
| 知识库范围：单库 / 全课程 | 知识库选择 + 范围切换 | `GET /api/courses`、`GET /api/courses/{id}/kb`、`PUT` {kbId,kbScope} | 已实现 |
| Agent 待确认动作卡（摘要+确认/取消） | 消息内动作卡片 | `GET /api/agent/actions?sessionId=`、`POST /api/agent/actions/{id}/confirm`、`/cancel` | 已实现 |
| 确认后按后端指示跳转（如自动练习） | `navigate` 经 `WebRoute` 翻译 | confirm 响应字段 | 已实现 |
| 从会话生成闪卡 | 会话菜单「生成闪卡」 | `POST /api/flashcards/generate` {mode:chat} | 已实现 |
| 额度徽标 + 次数不足提示 | 顶栏 QuotaBadge + 错误原文提示 | `GET /api/billing/overview` | 已实现（真机验证） |
| 知识图谱「去答疑」带问题跳入 | 路由 `chat?ask=&rel=` 自动成问 | 同上 | 已实现 |

## 4. Agent 可执行动作（后端 11 种 kind）

| kind | 实际写入 | Android 呈现 | 接口 | 状态 |
| --- | --- | --- | --- | --- |
| add_material | 收藏文本进知识库 | 确认卡，结果含文档名 | `POST /api/agent/actions/{id}/confirm` | 已实现 |
| schedule_review | 复习提醒通知 | 确认卡 | 同上 | 已实现 |
| finish_plan_task | 计划任务完成 | 确认卡 | 同上 | 已实现 |
| add_note | 新建学习笔记 | 确认卡 + 可跳笔记 | 同上 | 已实现 |
| favorite_question | 收藏题目 | 确认卡 | 同上 | 已实现 |
| generate_questions | 生成练习题并跳练习 | 确认卡 + `navigate=/practice?...` | 同上 | 已实现 |
| open_page | 页面跳转（白名单 16 项） | 路由翻译跳转 | 同上 | 已实现 |
| add_plan_task | 追加计划任务 | 确认卡 | 同上 | 已实现 |
| add_question | 题目录入 | 确认卡 | 同上 | 已实现 |
| add_flashcard | 新增闪卡 | 确认卡 | 同上 | 已实现 |
| add_mistake | 收录错题本 | 确认卡 | 同上 | 已实现 |
| 状态机 | pending→executed/cancelled/expired/failed，过期/已取消给出后端原文 | 卡片按状态置灰/移除 | — | 已实现（核心层） |

## 5. 对话厅（HallView）

| Web | Android | 接口 | 状态 |
| --- | --- | --- | --- |
| 频道列表（公共大厅 + 课程频道） | 频道侧栏/顶栏下拉 | `GET /api/hall/channels` | 已实现（真机验证） |
| 历史消息（≤50，升序） | 进入频道加载 | `GET /api/hall/messages` | 已实现（真机验证） |
| 实时群聊（Enter 发送，500 字上限） | WS `chat` 帧 + 输入框 | `ws(s)://…/ws/hall?token=` | 已实现 |
| 问助教（课程频道，AI 流式） | `ai_start/ai_delta/ai_error` 占位气泡 | WS `ai` 帧 | 已实现 |
| 在线名单（点按刷新、进出场系统消息） | 顶栏「在线 N 人」+ 进出场提示行 | WS `online`/`system` | 已实现（真机验证） |
| 断线重连（3s 递增、最多 5 次、手动重连按钮） | 顶栏「连接断开，正在重连…」+ 重新连接 | — | 已实现（核心层） |
| 好友列表（在线点 + 未读角标，30 秒轮询） | 好友区 | `GET /api/friends` | 已实现 |
| 加好友 / 同意 / 拒绝 / 删除好友（二次确认） | 好友区与私聊头部 | `POST /api/friends/request`、`/api/friends/{id}/accept`、`/reject`、`DELETE /api/friends/{id}` | 已实现 |
| 私聊（DM 帧、未读计数、进入即置已读） | 私聊会话界面 | WS `dm`、`GET /api/dm/messages` | 已实现 |
| 课程学习空间（共享笔记只读） | 课程频道「学习空间」Tab | `GET /api/hall/space/notes?courseId=` | 已实现 |
| AI 回答来源跳文档 | 引用芯片跳文档管理 | `GET /api/hall/messages` 的 `sources` | 已实现 |

## 6. 课程、知识库与文档

| Web | Android | 接口 | 状态 |
| --- | --- | --- | --- |
| 我的课程列表（角色/统计） | 我的课程页 | `GET /api/courses` | 已实现 |
| 创建课程（含入驻 Hub 开关） | 创建表单 | `POST /api/courses` | 已实现 |
| 设置/取消入驻 Hub（仅创建者） | 课程卡操作 | `POST /api/courses/{id}/hub` | 已实现 |
| 退出课程 / 删除课程（级联清理，二次确认） | 课程卡危险操作 | `DELETE /api/courses/{id}/enroll`、`DELETE /api/courses/{id}` | 已实现 |
| 课程 Hub 浏览与加入（关键词过滤、成员数） | 课程 Hub 页 | `GET /api/courses/hub`、`POST /api/courses/{id}/enroll` | 已实现 |
| 知识库增删 | 课程详情页知识库区 | `GET/POST /api/courses/{id}/kb`、`DELETE /api/kb/{id}` | 已实现 |
| 文档上传（PDF/DOCX/文本，≤20MB，旧 .doc 明确不支持） | 系统文件选择器（`OpenDocument`）+ 粘贴文本 | `POST /api/kb/{kbId}/documents/upload` | 已实现 |
| 文档重命名 / 删除（内联确认） | 文档行操作 | `PUT …/rename`、`DELETE …/{docId}` | 已实现 |
| 文档预览（正文+文档内搜索+上下个匹配） | 预览页（滚动定位） | `GET …/preview` | 已实现 |
| AI 速览（生成/重新生成、要点、可能考点） | 预览页速览卡 | `POST …/overview` | 已实现 |
| 原文件打开/分享 | 系统浏览器或系统应用 | `GET /files/kb-docs/{object}`（免鉴权） | 已实现 |
| 全库文档管理（课程筛选+关键词） | 文档管理页 | `GET /api/kb/docs` | 已实现 |
| 知识图谱生成/重新生成（含 stale 提示） | 图谱页 + 加载/错误态 | `GET /api/kb/{id}/graph`、`POST …/graph/rebuild` | 已实现 |
| 图谱交互（缩放/拖动/点节点） | 原生 Canvas 手势 + 邻居提示 | — | 已实现（组件） |
| 图谱节点跳答疑（带概念与关系） | 「去答疑」按钮 | 路由 `chat?ask=&rel=` | 已实现 |
| 向量索引重建（检索异常时） | 课程详情页「重建索引」 | `POST /api/kb/reindex` | 已实现 |

## 7. AI 内容生成

| Web | Android | 接口 | 状态 |
| --- | --- | --- | --- |
| 讲义流式生成（课程+主题+知识点+知识库范围） | AI 生成页讲义表单 + 流式预览 | `POST /api/generate/lecture/stream`（SSE，终帧 `saved`） | 已实现 |
| 讲义下载 .md | 「下载」→ 分享/存下载目录 | `GET /api/generate/content/{id}/download` | 已实现 |
| 讲义存入知识库（自动建库） | 「存入知识库」，成功显示库名 | `POST /api/generate/content/{id}/to-kb` | 已实现 |
| 断流后找回已保存讲义 | 生成历史补拉列表 | `GET /api/generate/history` | 已实现 |
| 练习题生成（1–10 题，自动入库） | 「生成练习题」+ 逐题展示 | `POST /api/generate/questions` | 已实现 |
| 额度徽标随生成刷新 | QuotaBadge | `GET /api/billing/overview` | 已实现 |

## 8. 练习、错题、收藏、考试、题库、闪卡

| Web | Android | 接口 | 状态 |
| --- | --- | --- | --- |
| 题量/知识点/难度自适应抽题 | 练习页设置（题量 1–20、知识点、专项） | `GET /api/practice/paper` | 已实现 |
| 错题重练 / 收藏重练 / 手动选题 | 入口带 mode 参数进入 | `/api/practice/paper?mistake=1`/`favorite=1`/`ids=` | 已实现 |
| 作答（单选/多选/判断/问答）与提交 | 分题型控件 + 答题卡 | `POST /api/practice/submit` | 已实现（真机验证） |
| 判分报告（得分、逐题解析、AI 批改要点） | 练习报告页 | `GET /api/practice/{id}` | 已实现（真机验证） |
| 报告内收藏本题 | 每题「收藏」 | `POST /api/favorites/toggle` | 已实现 |
| 历史练习列表（分页） | 练习页历史卡 | `GET /api/practice/history` | 已实现 |
| 错题本（课程筛选、分页、选项红绿对比、解析） | 错题本页 | `GET /api/mistakes` | 已实现 |
| 举一反三（1–4 道变式并当场作答判分） | 变式弹窗/子页 | `POST /api/mistakes/{id}/variants` + `POST /api/practice/submit` | 已实现 |
| 收录错题 / 已收录标记 | 题目行操作 | `POST /api/mistakes/{id}/collect`、`GET /api/mistakes/collected` | 已实现 |
| 收藏夹（取消收藏、收录错题、一键重练） | 收藏页 | `GET /api/favorites`、`/api/favorites/count`、toggle、collect | 已实现 |
| 考试列表（含最佳成绩/参与次数） | 考试页 | `GET /api/exams` | 已实现 |
| 手动组卷 / 自动组卷 | 创建考试表单 | `POST /api/exams`、`POST /api/exams/auto` | 已实现 |
| 限时作答（倒计时、超时仍受理、可续考） | 考试页倒计时 + 答题卡 | `POST /api/exams/{id}/start` | 已实现 |
| 交卷与成绩单（逐题对错、解析、收藏） | 成绩页 | `POST /api/exams/records/{id}/submit`、`GET …/report` | 已实现 |
| 我的考试记录 | 考试详情 | `GET /api/exams/{id}/my-records` | 已实现 |
| 题库管理（课程/类型/难度/关键词筛选、分页） | 题库页 | `GET /api/questions` | 已实现 |
| 题目录入/编辑（选项校验、判断题答案规范化） | 题目编辑页 | `POST /api/questions`、`PUT /api/questions/{id}` | 已实现 |
| 题目删除（被引用时后端拒绝并提示） | 删除二次确认 | `DELETE /api/questions/{id}` | 已实现 |
| 闪卡队列（到期优先）+ 翻面 + 记住/没记住 | 闪卡页 | `GET /api/flashcards/study`、`POST /api/flashcards/{id}/review` | 已实现 |
| 闪卡统计与删除 | 顶部统计 | `GET /api/flashcards/due`、`/count`、`DELETE /api/flashcards/{id}` | 已实现 |
| 闪卡生成（错题本 / 文档 / 会话） | 三种来源入口 | `POST /api/flashcards/generate` | 已实现 |
| 导出到 Anki（tab 分隔 txt） | 「导出」→ 分享/下载 | `GET /api/flashcards/export` | 已实现 |

## 9. 学情、计划、报告、笔记、搜索、通知、看板

| Web | Android | 接口 | 状态 |
| --- | --- | --- | --- |
| 掌握度列表（进度条 + 正确率） | 学情分析页 | `GET /api/progress/summary` | 已实现 |
| AI 复习建议（生成/重新生成、7 天缓存标记） | 学情页建议卡（Markdown） | `POST /api/progress/advice/refresh` | 已实现 |
| 课程学习时长明细 | 学情页时长表 | `GET /api/study/summary` | 已实现 |
| 学习计划生成（目标+天数+课程） | 计划页表单 | `POST /api/plans` | 已实现 |
| 计划详情与任务打卡（切换完成态） | 计划详情列表 | `GET /api/plans/{id}`、`POST /api/plans/tasks/{id}/checkin` | 已实现 |
| 打卡日历（月切换、绿/橙/灰三档） | 日历网格（原生组件） | `GET /api/plans/calendar?month=` | 已实现 |
| 删除计划 | 二次确认 | `DELETE /api/plans/{id}` | 已实现 |
| 学习报告列表/详情（Markdown） | 报告页 | `GET /api/reports`、`GET /api/reports/{id}` | 已实现 |
| 生成周报 | 课程选择 + 生成 | `POST /api/reports/weekly?courseId=` | 已实现 |
| 报告下载 PDF | 「下载 PDF」→ 系统打开/分享 | `GET /api/reports/{id}/pdf` | 已实现 |
| 学习笔记 CRUD | 笔记页 + 编辑页 | `GET/POST /api/notes`、`PUT /api/notes/{id}`、`DELETE` | 已实现 |
| 笔记共享到课程空间 | 共享开关（未绑定课程时禁用） | `PUT /api/notes/{id}/shared` | 已实现 |
| 全局搜索（题目/笔记/考试/文档片段分组） | 搜索页 | `GET /api/search?q=&courseId=` | 已实现 |
| 通知中心（系统通知 + 邮件记录两个 Tab） | 通知页 | `GET /api/notifications`、`GET /api/notifications/mails` | 已实现 |
| 未读数与实时刷新（SSE `event:refresh`） | 顶栏铃铛徽标 | `GET /api/notifications/unread-count`、`GET /api/notifications/stream` | 已实现（核心层） |
| 通知点击跳转（link 如 `/hall?peer=3`） | WebRoute 翻译跳转 | `POST /api/notifications/{id}/read` | 已实现 |
| 全部已读 | 通知页操作 | `POST /api/notifications/read-all` | 已实现 |
| 系统看板（用户/DAU/LLM 调用/14 天趋势） | 看板页（admin 可见） | `GET /api/admin/dashboard` | 已实现 |
| 用户列表（关键词+分页） | 看板页用户表 | `GET /api/admin/users` | 已实现 |
| 公告发布 / 撤回（含邮件通知开关） | 看板页公告区 | `POST/GET/DELETE /api/announcements` | 已实现 |

## 10. AI 次数与套餐（人工核对）

| Web | Android | 接口 | 状态 |
| --- | --- | --- | --- |
| 额度总览（是否开启、余额、初始次数） | 套餐页头部 + 顶栏徽标 | `GET /api/billing/overview` | 已实现 |
| 套餐列表（价格来自后端 `priceFen`，客户端不写死） | 套餐卡（分→元格式化） | `GET /api/billing/overview` | 已实现 |
| 下单（人工核对模式，不产生支付） | 「购买」创建待付款订单 | `POST /api/billing/orders` | 已实现（真机验证） |
| 付款说明展示（自由文本 + 可选 HTTPS 付款链接） | 说明卡 + 系统浏览器打开链接 | `paymentInstructions` / `paymentUrl` 字段 | 已实现（真机验证） |
| 提交交易信息进入待核对（爱发电/微信/支付宝/其他，备注≤120 字） | 订单行表单 | `POST /api/billing/orders/{no}/submit` | 已实现 |
| 更新已提交的付款信息 | 同上（REVIEW 可重提） | 同上 | 已实现 |
| 取消待付款订单 | 二次确认 | `POST /api/billing/orders/{no}/cancel` | 已实现 |
| 订单状态展示（待付款/待核对/已到账/已取消） | 状态标签 + 到账时间 | `GET /api/billing/orders` | 已实现（真机验证） |
| 未开放购买时禁用按钮 | 「暂未开放购买」 | `orderingAvailable=false` | 已实现 |
| 额度不足提示（生成预检失败原文） | 生成入口显示后端消息 | SSE 预检 JSON 信封 | 已实现（核心层） |
| 后台人工核对接口（`/internal/billing/*`，`X-Billing-Token`） | **客户端不实现**（Nginx 不代理，仅服务器本机） | — | 不适用 |

## 11. 平台能力与边界

| 要求 | Android 实现 | 状态 |
| --- | --- | --- |
| 不套 WebView | 全 Compose 原生；仅支付页/原文件用系统浏览器或系统应用打开 | 已实现 |
| 分层架构 | UI(Compose) / ViewModel / Repository / ApiClient(OkHttp) 四层，依赖经 `AppContainer` 注入 | 已实现 |
| 生命周期与状态恢复 | ViewModel + SavedState；SSE 流与会话绑定，进程重建后从服务端补拉消息 | 已实现 |
| HTTP 200 业务错误与 HTTP 401 双通道 | `ApiException(code)` + `SessionStore.signOut()` 自动回登录页 | 已实现 |
| 密钥不入客户端 | 无 AI Key / DB 口令 / `X-Internal-Token` / 支付运营 Token | 已实现 |
| 正式包 HTTPS/WSS | `network_security_config_secure`（明文默认禁止）；调试包或显式 `-PZHIXUE_RELEASE_ALLOW_HTTP=true` 才允许 HTTP | 已实现 |
| 服务器切换不串数据 | 登录态、会话 id、课程缓存、WS、通知按 `baseUrl` 分片重置 | 已实现 |
| 触控与适配 | 44dp 最小点击高度、底部导航+抽屉、字体缩放友好、表格/图谱可横滚缩放、软键盘 `adjustResize` | 已实现 |
| 数据隔离 | 一切以 `userId` 归属接口为准，按钮可见性不作为权限手段 | 已实现（接口层） |

## 12. 验证记录（2026-11-01，本机）

### 12.1 构建与自动化测试

| 命令（在 `mobile/android` 下） | 结果 |
| --- | --- |
| `./gradlew :app:testDebugUnitTest` | 通过：4 个测试类共 **59 个用例，0 失败**（SSE 成帧、信封错误、真实后端响应契约、Markdown、图表数学、路由翻译、按服务器分片的登录态隔离） |
| `./gradlew :app:assembleDebug` | 成功，产物 `app/build/outputs/apk/debug/app-debug.apk`（约 17.6 MB，applicationId `com.zhixue.assistant.debug`） |
| `./gradlew :app:assembleAndroidTest` | 成功 |
| `./gradlew :app:connectedDebugAndroidTest` | 通过：模拟器 `emulator-5554`（Android 17 / API 37，WHPX 加速）**5 个 Compose 界面用例，0 失败** |
| `./gradlew :app:compileReleaseKotlin` | 未单独执行；release 变体仅切换了明文策略与默认地址，改动集中在 `app/build.gradle.kts` |

测试暴露并修好的真实缺陷：

1. `SseFrameParser` 结尾无空行时丢最后一帧 → 已补发。
2. `ServerConfig` 只剥一个尾部斜杠（`https://a:8443///`）→ 改为 `trimEnd('/')`。
3. 对话厅 WebSocket 的 AI 帧 `role` 是字符串 `"ai"`，与 REST 的 0/1 冲突会导致整帧解析失败被丢弃 → 模型改为容错序列化，并补了回归用例。
4. `FileActions` 给 MediaStore 传了非法写入模式 `"wb"`，导出 xlsx 静默失败 → 改为 `"w"`，真机确认文件已落到 `/sdcard/Download/练习记录.xlsx`。
5. 答疑「新建会话后立即提问」时异步历史加载会覆盖进行中的气泡，回答永不上屏 → 本地未落库消息改为保留在尾部，且该会话在生成中不重拉历史。
6. 会话列表曾把「按答疑模式过滤」写进存储，切换模式后无会话可选 → 改为存全量、派生显示列表，切换时自动选中该模式的会话。
7. 深色模式**完全没有入口**（Web 顶栏有）→ 已在顶栏加「主题·自动/浅色/深色」切换，并用截图像素验证（浅色亮度采样 255/248，深色 27.7/17.7）。
8. 仪器测试最初 5 例全失败：debug 的 `applicationIdSuffix` 使 `ComponentActivity` 只存在于测试进程 → 在 `src/debug/AndroidManifest.xml` 声明宿主进程内 Activity 后全绿。

### 12.2 真机（模拟器）联调

环境：本机后端 `backend/target/learning-assistant-1.0.0.jar`（`mvn package` 重新构建）跑在 **8123**（8080 被其它项目占用，未动它），`ai-service` 跑在 8000，MySQL 为既有本地实例；次数包按 `BILLING_ENABLED=true` 打开以验证人工核对流程。模拟器通过 `adb reverse tcp:8123 tcp:8123` 连本机后端（跨宿主机网络被防火墙拦，属环境问题，不是客户端缺陷）。

| 场景 | 观察到的事实 |
| --- | --- |
| 登录 / 登录态恢复 | 欢迎页 → 登录页填 `pg13` → 首页显示「你好，泡椒13」；重启 App 直接进首页，无需重新登录 |
| 首页真实数据 | 累计学习 25 小时 9 分、活跃 19 天、连续 1 天、练习 26 次、收藏 3、待攻克错题 17、今日到期闪卡 38，均来自后端接口 |
| 答疑流式 | 提问后回答字符量在 4s→7s 间从 194 增至 334，边生成边上屏；结束后出现「继续问」三条推荐追问 |
| 流式中切会话 | 生成中切到「你好」会话：该会话页面不含本次提问内容；切回后原会话回答完整保留，无串写、无丢失 |
| 额度扣减 | 三次使用后个人中心显示「剩余 7 次」，与后端 `/api/billing/overview` 的 `balance=7` 一致 |
| 对话厅实时 | 进入即显示「# 公共大厅」「在线 1 人：泡椒13」「泡椒13 进入对话厅」，在线与进出场来自 WebSocket 帧；频道/好友两页可切 |
| 练习闭环 | 抽到 Python 课程 5 题（答题卡 1–5）→ 作答 → 「提交并 AI 批改」→ 练习报告：0/50、答对 0/5、正确率 0%，逐题显示我的答案与「正确答案」标注，另有再做一组/错题重练/收藏重练 |
| 数据导出 | 个人中心「导出练习记录」→ 设备 `/sdcard/Download/练习记录.xlsx` 出现文件 |
| 次数包下单 | 「选择套餐」→ 提示「订单已创建…」；订单卡显示 `LA18C0F2E8…`、「待付款」、平台下拉（爱发电/微信/支付宝/其他）、交易单号输入（≤120 字）、提交付款信息与取消订单按钮；后端 `/api/billing/orders` 复核为 `WAITING` |
| 深色模式 | 顶栏切换三种模式并持久化，截图像素亮度确认明暗生效 |

未逐项真机走查（代码与接口已接通、编译与自动化测试通过）：文档上传与 AI 速览、知识图谱交互、引用原文点按、Agent 动作确认（需服务端真实提议）、在线考试计时与成绩回顾、题库 CRUD、闪卡学习与导出、学习计划/报告/笔记、全局搜索、通知中心、系统看板、订单的提交与取消点按（表单渲染与后端合同已单独验证）。

遗留的本地测试数据：`learning_assistant` 库里给 `pg13` 留下一条 `WAITING` 订单 `LA18C0F2E8A7404BC7A46121A90ECAFCD3`，可在 App 里取消或删除该行；本次联调还新增了若干答疑会话与一条练习记录。

