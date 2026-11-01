package com.zhixue.assistant.ui.screens.course

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhixue.assistant.AppContainer
import com.zhixue.assistant.core.UiState
import com.zhixue.assistant.core.userMessage
import com.zhixue.assistant.data.model.CourseCard
import com.zhixue.assistant.ui.components.BannerTone
import com.zhixue.assistant.ui.components.EmptyView
import com.zhixue.assistant.ui.components.ErrorState
import com.zhixue.assistant.ui.components.LabeledField
import com.zhixue.assistant.ui.components.LoadingView
import com.zhixue.assistant.ui.components.LocalAppContainer
import com.zhixue.assistant.ui.components.LocalNavigator
import com.zhixue.assistant.ui.components.PageHeader
import com.zhixue.assistant.ui.components.SecondaryButton
import com.zhixue.assistant.ui.components.ZhixueCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 课程 Hub：浏览已入驻的课程并加入/退出。
 * enrolled 与 memberCount 先做乐观更新，请求失败再回滚，和 Web 端一致。
 */
class HubViewModel(private val container: AppContainer) : ViewModel(), MessageHost {

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { HubViewModel(container) }
        }
    }

    private val _state = MutableStateFlow<UiState<List<CourseCard>>>(UiState.Loading)
    val state: StateFlow<UiState<List<CourseCard>>> = _state.asStateFlow()

    private val _keyword = MutableStateFlow("")
    val keyword: StateFlow<String> = _keyword.asStateFlow()

    private val _busyId = MutableStateFlow<Long?>(null)
    val busyId: StateFlow<Long?> = _busyId.asStateFlow()

    private val _message = MutableStateFlow<UiMessage?>(null)
    override val message: StateFlow<UiMessage?> = _message.asStateFlow()
    private var seq = 0L

    init {
        load()
    }

    override fun clearMessage() {
        _message.value = null
    }

    private fun emit(text: String, tone: BannerTone = BannerTone.Info) {
        seq += 1
        _message.value = UiMessage(seq, text, tone)
    }

    fun load() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            runCatching { container.course.hub() }
                .onSuccess { _state.value = UiState.Ready(it) }
                .onFailure { _state.value = UiState.Error(it.userMessage()) }
        }
    }

    fun onKeywordChange(value: String) {
        _keyword.value = value
    }

    fun allCourses(): List<CourseCard> = (_state.value as? UiState.Ready)?.data.orEmpty()

    fun visibleCourses(): List<CourseCard> {
        val all = allCourses()
        val kw = _keyword.value.trim().lowercase()
        if (kw.isEmpty()) return all
        return all.filter { course ->
            listOf(course.name, course.description, course.ownerName)
                .any { field -> field?.lowercase()?.contains(kw) == true }
        }
    }

    fun enroll(course: CourseCard) {
        _busyId.value = course.id
        applyChange(course.id, enrolled = true, delta = 1)
        viewModelScope.launch {
            val result = runCatching { container.course.enroll(course.id) }
            result.onSuccess {
                emit("已加入课程「${course.name}」，可在「我的课程」里查看", BannerTone.Success)
                runCatching { container.courses.ensure(force = true) }
            }.onFailure { error ->
                applyChange(course.id, enrolled = false, delta = -1)
                emit(error.userMessage(), BannerTone.Error)
            }
            _busyId.value = null
        }
    }

    fun unenroll(course: CourseCard) {
        _busyId.value = course.id
        applyChange(course.id, enrolled = false, delta = -1)
        viewModelScope.launch {
            val result = runCatching { container.course.unenroll(course.id) }
            result.onSuccess {
                emit("已退出课程「${course.name}」", BannerTone.Success)
                runCatching { container.courses.ensure(force = true) }
            }.onFailure { error ->
                applyChange(course.id, enrolled = true, delta = 1)
                emit(error.userMessage(), BannerTone.Error)
            }
            _busyId.value = null
        }
    }

    private fun applyChange(courseId: Long, enrolled: Boolean, delta: Int) {
        val current = _state.value
        if (current !is UiState.Ready) return
        _state.value = UiState.Ready(
            current.data.map { course ->
                if (course.id != courseId) course
                else course.copy(
                    enrolled = enrolled,
                    memberCount = ((course.memberCount ?: 0) + delta).coerceAtLeast(0),
                )
            }
        )
    }
}

@Composable
fun HubScreen() {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val vm: HubViewModel = viewModel(factory = HubViewModel.factory(container))
    val state by vm.state.collectAsStateWithLifecycle()
    val keyword by vm.keyword.collectAsStateWithLifecycle()
    val busyId by vm.busyId.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .padding(horizontal = 14.dp)
                .padding(top = 12.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PageHeader("课程 Hub", "发现并加入其他人公开的课程，加入后会出现在「我的课程」")
            MessageChannel(vm)
        }

        Box(Modifier.weight(1f)) {
            when (val s = state) {
                UiState.Loading -> LoadingView("加载 Hub 课程中…", Modifier.fillMaxSize())

                is UiState.Error -> ErrorState(
                    s.message,
                    onRetry = { vm.load() },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                is UiState.Ready -> HubList(vm, s.data.isEmpty(), busyId, keyword, onRetry = { vm.load() })
            }
        }
    }
}

@Composable
private fun HubList(
    vm: HubViewModel,
    hubEmpty: Boolean,
    busyId: Long?,
    keyword: String,
    onRetry: () -> Unit,
) {
    val navigator = LocalNavigator.current
    if (hubEmpty) {
        LazyColumn(contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 20.dp)) {
            item {
                EmptyView(
                    title = "还没有课程入驻 Hub",
                    hint = "在「我的课程」创建课程，或把已创建的课程加入 Hub",
                )
            }
        }
        return
    }
    val visible = vm.visibleCourses()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ZhixueCard {
                LabeledField(
                    label = "搜索课程",
                    value = keyword,
                    onValueChange = vm::onKeywordChange,
                    placeholder = "按课程名 / 简介 / 创建者搜索…",
                )
                Spacer(Modifier.height(8.dp))
                MetaLine("${visible.size} / ${vm.allCourses().size} 门课程")
            }
        }
        if (visible.isEmpty()) {
            item {
                EmptyView(
                    title = "没有匹配「${keyword.trim()}」的课程",
                    hint = "换个关键词试试；确认对方课程已入驻 Hub 后下拉刷新",
                    actionText = "重新加载",
                    onAction = onRetry,
                )
            }
        } else {
            items(visible, key = { it.id }) { course ->
                CourseInfoCard(course = course, busy = busyId == course.id) {
                    if (course.enrolled != true) {
                        SecondaryButton(
                            text = if (busyId == course.id) "加入中…" else "加入课程",
                            onClick = { vm.enroll(course) },
                            enabled = busyId == null,
                        )
                    } else {
                        SecondaryButton("去练习", onClick = { navigator("/practice?courseId=${course.id}") })
                        if (!course.isCreator) {
                            SecondaryButton(
                                text = if (busyId == course.id) "退出中…" else "退出课程",
                                onClick = { vm.unenroll(course) },
                                enabled = busyId == null,
                                danger = true,
                            )
                        }
                    }
                }
            }
        }
    }
}
