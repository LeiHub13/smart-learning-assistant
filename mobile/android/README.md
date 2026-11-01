# 智学助手 · 原生 Android 客户端

Kotlin + Jetpack Compose 的原生 Android 工程，复刻 Web 端（`frontend/`）的全部功能与业务流程，
直连现有 Java 后端（`backend/`，8080），**不新增业务后端、不让客户端直连 ai-service 或任何 `/internal/*` 接口**。

- 功能与验收清单：[FEATURE_MATRIX.md](FEATURE_MATRIX.md)
- 旧的 uni-app 移动端仍保留在 `mobile/` 上层目录（`pages/`、`utils/` 等）作交互参考，不参与本工程的构建。

## 1. 用 Android Studio 打开

1. Android Studio → Open → 选择 **`mobile/android` 目录**（不是 `mobile`，也不是仓库根）。
2. 首次同步会下载 Gradle 9.7.1 与依赖（`gradle/wrapper/gradle-wrapper.properties` 已固定版本）。
3. `local.properties` 里的 `sdk.dir` 由 Android Studio 自动写入；命令行构建需要它存在：
   ```
   sdk.dir=C\:\\Users\\<你>\\AppData\\Local\\Android\\Sdk
   ```
4. **JDK 要求**：实测 **JDK 17 即可构建**（`JAVA_HOME` 指向 17，Gradle 9.7.1 + AGP 9.4.1）；Android Studio 自带的 JBR 同样可用。
   不要在工程里保留 `gradle/gradle-daemon-jvm.properties`：它是 `updateDaemonJvm`/`gradle wrapper` 生成的守护进程 JVM 锁定文件，
   会把构建强行钉到 JDK 25，本机没有时就去联网下载 JDK。本工程已删除该文件，若你的 IDE 重新生成它而又只有 JDK 17，删掉即可。
5. 仓库路径含中文（如 `F:\毕设`）时，AGP 默认拒绝构建，本工程已在 `gradle.properties` 里设
   `android.overridePathCheck=true`，实测可正常编译；若遇到资源/AOT 相关的怪问题，把工程复制到纯 ASCII 路径再构建。

## 2. 配置后端地址

地址有三个层次，优先级从高到低：

| 层次 | 位置 | 用途 |
| --- | --- | --- |
| 编译期默认值 | `-PZHIXUE_DEBUG_URL` / `-PZHIXUE_RELEASE_URL`（或 `local.properties` 同名键） | 调试包默认 `http://10.0.2.2:8080`，正式包默认 `https://101.37.65.217` |
| 运行时修改 | App 内「服务器设置」页 / 登录页可展开的地址栏 | 模拟器、局域网真机、线上环境切换，无需重新打包 |
| 历史地址 | 「服务器设置」里的最近地址 | 快速回切 |

- 模拟器访问本机后端：`http://10.0.2.2:8080`
- 真机访问本机后端：手机与电脑同网段，填电脑局域网 IP，如 `http://192.168.1.5:8080`
- **按服务器分片保存登录态**：token、`/api/auth/me` 缓存、上次会话 id 都以「 scheme+host+port 」为键存放，
  切换到另一台后端时不会把旧服务器的 JWT 或会话 id 发过去（见 `core/ServerConfig.kt`、`core/SessionStore.kt`）。
- 明文 HTTP：调试包允许；正式包默认只允许 HTTPS/WSS（`network_security_config_secure.xml`）。
  自托管还没有证书时，可临时用 `-PZHIXUE_RELEASE_ALLOW_HTTP=true` 出包（App 会在服务器设置页显示当前处于明文模式）。

## 3. 构建与安装

```bash
cd mobile/android

# 调试包（日常开发用）
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
# applicationId：com.zhixue.assistant.debug，可与正式包共存

# 安装到已连接的设备/模拟器
./gradlew :app:installDebug
# 或 adb install -r app/build/outputs/apk/debug/app-debug.apk

# 单元测试（JVM，不需要设备）
./gradlew :app:testDebugUnitTest

# 仪器测试与 Compose UI 测试（需要模拟器或真机；仅编译验证用 assembleAndroidTest）
./gradlew :app:assembleAndroidTest
./gradlew :app:connectedDebugAndroidTest

# 正式包（默认 HTTPS）
./gradlew :app:assembleRelease
```

调试日志：`adb logcat -s ZhixueAndroid`；网络与流式相关的错误在界面上都会直接显示后端返回的中文 message。

## 4. 工程结构

```
app/src/main/java/com/zhixue/assistant/
├── AppContainer.kt              # 手写依赖图：三套 OkHttp（REST / SSE / WebSocket）+ 各仓库 + 全局服务
├── MainActivity.kt / ZhixueApp.kt
├── core/                        # 网络与状态底座
│   ├── ApiClient.kt             # {code,message,data} 信封、401 处理、multipart、字节下载
│   ├── SseClient.kt             # POST SSE：按行成帧、UTF-8 分片安全、json 预检错误、断流语义
│   ├── ServerConfig.kt          # 后端地址归一化 / ws 同源 / 明文策略
│   ├── SessionStore.kt          # 登录态按服务器分片（EncryptedSharedPreferences）
│   ├── CourseCache.kt / NotificationCenter.kt / StudyHeartbeat.kt / SettingsStore.kt
│   └── UiState.kt               # Loading / Error(可重试) / Ready
├── data/
│   ├── model/                   # ChatModels / CourseModels / LearnModels / HallModels / InsightModels
│   └── repo/                    # 与后端接口一一对应的仓库 + HallSocket（对话厅 WebSocket）
├── ui/
│   ├── AppRoot.kt               # 登录态门禁 + CompositionLocal（容器 / 提示 / 路由翻译）
│   ├── nav/                     # Routes / AppNavGraphs / AppScaffold（顶栏+底栏+抽屉）/ WebRoute（后端 navigate 翻译）
│   ├── components/              # Markdown、图表、热力图、知识图谱、头像、表单与状态组件
│   └── screens/<domain>/        # 各功能页 + ViewModel
└── util/                        # Markdown 解析、格式化、图表数学、文件保存与分享
```

设计要点：

- **不使用 WebView**：Markdown、表格、代码块、图表、热力图、知识图谱都是 Compose/Canvas 原生绘制；
  第三方支付页与文档原文件用系统浏览器 / 系统应用打开。
- **流式回答**：答疑与讲义都是真流式（`POST` + SSE 增量），每条流绑定所属会话，生成中切会话不会串写；
  断流只提示「连接中断，请稍后查看生成结果」，**不会**当作失败去退款或自动重发。
- **Agent 动作**：只展示后端下发的待确认动作，确认才写库，确认结果里的 `navigate` 由 `WebRoute` 翻译成 Android 目的地。
- **次数包人工核对**：价格、余额、付款说明、订单状态全部来自后端；客户端只提交交易信息，绝不伪造到账。
- **权限模型不变**：没有引入任何新的角色概念，按钮可见性只是体验，实际以接口鉴权结果为准。

## 5. 常见问题：登录/请求超时（Android 16+）

现象：后端在本机明明能用浏览器/curl 打开，App 里登录一直提示「连接后端超时」；用
`adb shell` 里的 `nc/curl` 访问同一个地址却是通的。

原因：**Android 16（API 36）起，访问局域网地址（`10.0.2.2`、`192.168.x.x`、`10.x` 等）需要
「本地网络」权限**（`android.permission.ACCESS_LOCAL_NETWORK`）。没有该权限时系统的 netd 会直接丢弃
这些连接，客户端只能看到超时（shell 不受此限制，所以特别容易误判成后端没起）。

处理（二选一）：

1. **允许权限**（推荐，App 内已内置引导）：清单已声明该权限，App 检测到当前后端是局域网地址且未授权时，
   顶部会出现「需要『本地网络』权限」提示，点「授予权限」即可；也可以在系统设置 → 应用 → 智学助手 → 权限里打开。
   命令行也可：`adb shell pm grant com.zhixue.assistant.debug android.permission.ACCESS_LOCAL_NETWORK`
2. **绕开局域网**：`adb reverse tcp:8080 tcp:8080` 后把 App 里的服务器地址改成 `http://127.0.0.1:8080`
   （回环地址不触发该限制）。真机连电脑后端时仍建议走方案 1，并确认 Windows 防火墙放行 8080 入站。

该权限只在 API 36+ 存在；低于该版本的设备不受影响。正式环境走 HTTPS 公网域名时也用不到它。

## 6. 已知限制

- 「导出练习记录 / 考试成绩」xlsx、周报 PDF、闪卡 Anki txt 都通过系统下载目录或分享面板落地，
  不同 ROM 的文件管理器入口略有差异。
- 对话厅的 AI 助教是单课程串行（后端限制），连续点两次「问助教」会收到「助教正在回答中」。
- 正式包的 HTTPS 依赖部署侧证书；当前服务器仍是 IP + HTTP，需要用正式包连它时加
  `-PZHIXUE_RELEASE_ALLOW_HTTP=true`，或先按 `DEPLOYMENT.md` 把证书配好。

## 7. 构建与验证结果（2026-11-01 本机实测）

| 命令 | 结果 |
| --- | --- |
| `./gradlew :app:testDebugUnitTest` | **59 个 JVM 用例全绿**：SSE 成帧与断流语义、`{code,message,data}` 双通道错误、真实后端响应契约、Markdown 解析、图表数学、Web 路由翻译、按服务器分片的登录态隔离 |
| `./gradlew :app:assembleDebug` | 成功，产物 `app/build/outputs/apk/debug/app-debug.apk`（约 17.6 MB，`com.zhixue.assistant.debug`） |
| `./gradlew :app:connectedDebugAndroidTest` | 在模拟器（Android 17 / API 37，WHPX）**5 个 Compose 界面用例全绿** |
| 真机联调 | 模拟器连本机后端（8123）+ ai-service（8000）：登录与登录态恢复、首页真实统计、答疑流式逐字上屏与「生成中切会话再切回不串写」、推荐追问、额度扣减（10→7）、对话厅 WebSocket 在线名单与进出场、练习出题→提交→判分报告、xlsx 导出落到设备「下载」目录、次数包下单与订单卡（`WAITING`）、深色模式（像素采样验证明暗） |

完整逐项结论与「未真机走查清单」见 [FEATURE_MATRIX.md 第 12 节](FEATURE_MATRIX.md)。

已知限制补充：跨宿主机访问（模拟器 → 电脑局域网 IP）会被 Windows 防火墙拦住，联调时用
`adb reverse tcp:8123 tcp:8123` + App 内地址 `http://127.0.0.1:8123`，或给本机端口放行入站规则。

## 8. 已实现范围概览

- 34 个 Android 目的地覆盖 Web 的 22 个视图与全部子流程：账号（登录/注册/验证码/找回/资料/头像/密码/通知偏好/导出）、
  首页看板、智能答疑（会话/流式/引用/追问/苏格拉底/费曼/闪卡/Agent 动作）、对话厅（频道/实时/好友/私聊/学习空间）、
  课程与 Hub、知识库与文档（上传/预览/速览/重命名/删除/原文件）、知识图谱、AI 讲义与练习题、
  练习/错题/收藏/考试/题库/闪卡、学情/计划/报告/笔记、全局搜索、通知中心（含邮件记录）、系统看板、
  AI 次数与套餐（人工核对）、服务器设置。
- 后端零改动：所有接口沿用现有 `backend`，未新增角色或权限，`/internal/*` 与支付运营接口不由客户端调用。

