package com.zhixue.assistant

import android.app.Application
import android.content.Context
import com.zhixue.assistant.core.ApiClient
import com.zhixue.assistant.core.CourseCache
import com.zhixue.assistant.core.NotificationCenter
import com.zhixue.assistant.core.PreferencesStore
import com.zhixue.assistant.core.SecureStore
import com.zhixue.assistant.core.ServerConfig
import com.zhixue.assistant.core.SessionStore
import com.zhixue.assistant.core.SettingsStore
import com.zhixue.assistant.core.SseClient
import com.zhixue.assistant.core.StudyHeartbeat
import com.zhixue.assistant.data.repo.AdminRepository
import com.zhixue.assistant.data.repo.AuthRepository
import com.zhixue.assistant.data.repo.BillingRepository
import com.zhixue.assistant.data.repo.ChatRepository
import com.zhixue.assistant.data.repo.CourseRepository
import com.zhixue.assistant.data.repo.ExamRepository
import com.zhixue.assistant.data.repo.FlashcardRepository
import com.zhixue.assistant.data.repo.GenerateRepository
import com.zhixue.assistant.data.repo.HallRepository
import com.zhixue.assistant.data.repo.HallSocket
import com.zhixue.assistant.data.repo.NotificationRepository
import com.zhixue.assistant.data.repo.PracticeRepository
import com.zhixue.assistant.data.repo.QuestionBankRepository
import com.zhixue.assistant.data.repo.StudyRepository
import com.zhixue.assistant.data.model.BillingOverview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 手写依赖图（不引 Hilt，减少构建复杂度）：进程内单例，ViewModel 通过 CompositionLocal 拿到它。
 * 三个 OkHttpClient 共享连接池，但超时策略不同：
 *  - http        REST，读超时 60s
 *  - streaming   SSE 不能被读超时截断，readTimeout=0
 *  - websocket   由协议层心跳保活
 */
class AppContainer(app: Application) {

    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val plainPrefs = PreferencesStore(app.getSharedPreferences("zhixue_prefs", Context.MODE_PRIVATE))
    val secure: SecureStore = SecureStore.create(app)

    val settings = SettingsStore(plainPrefs)

    val server = ServerConfig(
        store = plainPrefs,
        defaultBaseUrl = BuildConfig.DEFAULT_BASE_URL,
        allowHttp = BuildConfig.DEBUG || BuildConfig.ALLOW_CLEARTEXT,
    )

    val session = SessionStore(secure.store, server)

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val streamingHttp: OkHttpClient by lazy {
        http.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    private val socketHttp: OkHttpClient by lazy {
        http.newBuilder()
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    val api: ApiClient by lazy {
        ApiClient(server, session, http, onSessionExpired = ::handleSessionExpired)
    }

    val sse: SseClient by lazy { SseClient(streamingHttp, server, session) }

    val auth by lazy { AuthRepository(api, session) }
    val chat by lazy { ChatRepository(api, sse) }
    val hall by lazy { HallRepository(api) }
    val course by lazy { CourseRepository(api) }
    val generate by lazy { GenerateRepository(api, sse) }
    val practice by lazy { PracticeRepository(api) }
    val exam by lazy { ExamRepository(api) }
    val questionBank by lazy { QuestionBankRepository(api) }
    val flashcard by lazy { FlashcardRepository(api) }
    val study by lazy { StudyRepository(api) }
    val notification by lazy { NotificationRepository(api, sse) }
    val billing by lazy { BillingRepository(api) }
    val admin by lazy { AdminRepository(api) }

    val courses = CourseCache(course)

    val notifications = NotificationCenter(notification, session, scope)

    val heartbeat = StudyHeartbeat(study, session, scope)

    val hallSocket: HallSocket by lazy {
        HallSocket(
            websocketUrl = { server.websocketUrl("/ws/hall") },
            token = { session.accessToken },
            client = socketHttp,
            scope = scope,
        )
    }

    /** AI 次数：任何一次生成结束、下单、核对到账后都要重拉，和 Web 的 billing-changed 事件等价。 */
    private val _quota = MutableStateFlow<BillingOverview?>(null)
    val quota: StateFlow<BillingOverview?> = _quota.asStateFlow()

    fun refreshQuota() {
        scope.launch {
            runCatching { billing.overview() }.onSuccess { _quota.value = it }
        }
    }

    init {
        server.onChange { handleServerChanged() }
    }

    /** 换后端：所有服务器相关状态必须整体重置，避免旧 token / 旧会话 id 打到新服务器。 */
    private fun handleServerChanged() {
        courses.clear()
        _quota.value = null
        hallSocket.stop()
        notifications.stop()
        heartbeat.stop()
        session.restore()
        if (session.isLoggedIn) {
            notifications.start()
            heartbeat.start()
            refreshQuota()
            scope.launch { runCatching { auth.fetchMe() } }
        }
    }

    private fun handleSessionExpired() {
        courses.clear()
        _quota.value = null
        hallSocket.stop()
        notifications.stop()
        heartbeat.stop()
    }

    /** 登录成功后的统一启动动作。 */
    fun onSignedIn() {
        notifications.start()
        heartbeat.start()
        refreshQuota()
        scope.launch { runCatching { auth.fetchMe() } }
        scope.launch { runCatching { courses.ensure(force = true) } }
    }

    /** 退出登录：清当前服务器分片的登录态与所有派生缓存。 */
    fun onSignedOut() {
        session.signOut()
        handleSessionExpired()
    }

    fun bootIfSignedIn() {
        if (session.isLoggedIn) {
            onSignedIn()
            scope.launch { runCatching { auth.fetchMe() } }
        }
    }
}

val Application.container: AppContainer
    get() = (this as ZhixueApp).container
