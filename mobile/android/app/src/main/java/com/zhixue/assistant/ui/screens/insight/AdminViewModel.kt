package com.zhixue.assistant.ui.screens.insight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhixue.assistant.AppContainer
import com.zhixue.assistant.core.UiState
import com.zhixue.assistant.core.userMessage
import com.zhixue.assistant.data.model.AdminDashboard
import com.zhixue.assistant.data.model.AdminUserPage
import com.zhixue.assistant.data.model.AnnouncementDto
import com.zhixue.assistant.data.model.DashboardPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 场景中文名与 Web AdminView 的 SCENE_LABELS 一致。 */
val ADMIN_SCENE_LABELS = mapOf(
    "rag_qa" to "知识库答疑",
    "free" to "自由对话",
    "agent" to "智能答疑",
    "lecture" to "讲义生成",
    "questions" to "AI 出题",
    "review" to "主观题批改",
    "advice" to "复习建议",
    "rewrite" to "查询改写",
    "rerank" to "相关性重排",
    "plan" to "学习计划",
    "report" to "学习报告",
    "recommend" to "今日推荐",
    "doc_overview" to "文档速览",
    "followup" to "追问推荐",
    "unknown" to "未知场景",
)

fun adminSceneLabel(scene: String): String = ADMIN_SCENE_LABELS[scene] ?: scene

const val ADMIN_USER_PAGE_SIZE = 10
const val ANNOUNCEMENT_TITLE_MAX = 100
const val ANNOUNCEMENT_CONTENT_MAX = 5000

/** 趋势图可切换的指标，字段名与后端 series 一致。 */
enum class AdminMetric(val label: String, val valueOf: (DashboardPoint) -> Number) {
    REGISTRATIONS("注册用户", { it.registrations }),
    DAU("活跃用户", { it.dau }),
    MINUTES("学习分钟", { it.minutes }),
    LLM_CALLS("LLM 调用", { it.llmCalls }),
    LLM_TOKENS("tokens", { it.llmTokens }),
}

class AdminViewModel(private val container: AppContainer) : ViewModel() {

    private val _dashboard = MutableStateFlow<UiState<AdminDashboard>>(UiState.Loading)
    val dashboard: StateFlow<UiState<AdminDashboard>> = _dashboard.asStateFlow()

    private val _users = MutableStateFlow<UiState<AdminUserPage>?>(null)
    val users: StateFlow<UiState<AdminUserPage>?> = _users.asStateFlow()

    private val _announcements = MutableStateFlow<UiState<List<AnnouncementDto>>>(UiState.Loading)
    val announcements: StateFlow<UiState<List<AnnouncementDto>>> = _announcements.asStateFlow()

    private val _metric = MutableStateFlow(AdminMetric.REGISTRATIONS)
    val metric: StateFlow<AdminMetric> = _metric.asStateFlow()

    private val _keyword = MutableStateFlow("")
    val keyword: StateFlow<String> = _keyword.asStateFlow()

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _content = MutableStateFlow("")
    val content: StateFlow<String> = _content.asStateFlow()

    private val _notifyEmail = MutableStateFlow(true)
    val notifyEmail: StateFlow<Boolean> = _notifyEmail.asStateFlow()

    private val _publishing = MutableStateFlow(false)
    val publishing: StateFlow<Boolean> = _publishing.asStateFlow()

    private val _recallingId = MutableStateFlow<Long?>(null)
    val recallingId: StateFlow<Long?> = _recallingId.asStateFlow()

    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message.asStateFlow()

    var isAdmin: Boolean = false
        private set

    init {
        load()
    }

    fun dismissMessage() {
        _message.value = null
    }

    fun setMetric(metric: AdminMetric) {
        _metric.value = metric
    }

    fun setKeyword(value: String) {
        _keyword.value = value
    }

    fun setTitle(value: String) {
        _title.value = value.take(ANNOUNCEMENT_TITLE_MAX)
    }

    fun setContent(value: String) {
        _content.value = value.take(ANNOUNCEMENT_CONTENT_MAX)
    }

    fun setNotifyEmail(value: Boolean) {
        _notifyEmail.value = value
    }

    fun load() {
        _dashboard.value = UiState.Loading
        viewModelScope.launch {
            // me 只在登录/启动时拉过一次，冷启动直接进入本页时可能还是空
            val me = container.session.me ?: runCatching { container.auth.fetchMe() }.getOrNull()
            isAdmin = me?.admin == true
            if (!isAdmin) {
                _dashboard.value = UiState.Error("无权访问管理看板")
                _users.value = null
                return@launch
            }
            runCatching { container.admin.dashboard() }
                .fold(
                    onSuccess = { _dashboard.value = UiState.Ready(it) },
                    onFailure = { _dashboard.value = UiState.Error(it.userMessage()) },
                )
            loadUsers(1)
            loadAnnouncements()
        }
    }

    fun loadUsers(page: Int) {
        if (!isAdmin) return
        _users.value = UiState.Loading
        viewModelScope.launch {
            runCatching { container.admin.users(_keyword.value, page, ADMIN_USER_PAGE_SIZE) }
                .fold(
                    onSuccess = { _users.value = UiState.Ready(it) },
                    onFailure = {
                        _users.value = UiState.Error(it.userMessage())
                        _message.value = UiMessage("用户列表加载失败：${it.userMessage()}", error = true)
                    },
                )
        }
    }

    fun searchUsers() = loadUsers(1)

    fun loadAnnouncements() {
        if (!isAdmin) return
        _announcements.value = UiState.Loading
        viewModelScope.launch {
            runCatching { container.admin.announcements() }
                .fold(
                    onSuccess = { _announcements.value = UiState.Ready(it) },
                    onFailure = { _announcements.value = UiState.Error(it.userMessage()) },
                )
        }
    }

    fun publish() {
        if (_publishing.value) return
        val title = _title.value.trim()
        val validation = when {
            title.isEmpty() -> "公告标题不能为空"
            title.length > ANNOUNCEMENT_TITLE_MAX -> "公告标题过长（≤$ANNOUNCEMENT_TITLE_MAX 字）"
            _content.value.isBlank() -> "公告内容不能为空"
            _content.value.length > ANNOUNCEMENT_CONTENT_MAX -> "公告内容过长（≤$ANNOUNCEMENT_CONTENT_MAX 字）"
            else -> null
        }
        if (validation != null) {
            _message.value = UiMessage(validation, error = true)
            return
        }
        _publishing.value = true
        _message.value = null
        viewModelScope.launch {
            runCatching { container.admin.publish(title, _content.value, _notifyEmail.value) }
                .onSuccess {
                    _message.value = UiMessage(
                        if (_notifyEmail.value) "已发布并推送给全部用户（含邮件）" else "已发布，仅站内通知（撤回零残留）",
                        error = false,
                    )
                    _title.value = ""
                    _content.value = ""
                    loadAnnouncements()
                }
                .onFailure { _message.value = UiMessage("发布失败：${it.userMessage()}", error = true) }
            _publishing.value = false
        }
    }

    /** 撤回公告会同时清理已扇出到各用户铃铛的通知；邮件无法收回。 */
    fun recall(announcement: AnnouncementDto) {
        if (_recallingId.value != null) return
        _recallingId.value = announcement.id
        _message.value = null
        viewModelScope.launch {
            runCatching { container.admin.recall(announcement.id) }
                .onSuccess {
                    _message.value = UiMessage("已撤回，清理了 ${it.recalled} 条用户通知", error = false)
                    loadAnnouncements()
                }
                .onFailure { _message.value = UiMessage("撤回失败：${it.userMessage()}", error = true) }
            _recallingId.value = null
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { AdminViewModel(container) }
        }
    }
}
