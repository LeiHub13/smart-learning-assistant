package com.zhixue.assistant.ui.screens.learn

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhixue.assistant.AppContainer
import com.zhixue.assistant.core.UiState
import com.zhixue.assistant.core.userMessage
import com.zhixue.assistant.data.model.CourseCard
import com.zhixue.assistant.data.model.FavoriteItem
import com.zhixue.assistant.ui.nav.Routes
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 收藏夹：课程筛选 + 取消收藏 + 收录错题本 + 一键重练；计数走 /api/favorites/count。 */
class FavoritesViewModel(private val container: AppContainer) : ViewModel() {

    data class State(
        val courses: List<CourseCard> = emptyList(),
        val courseId: Long? = null,
        val items: UiState<List<FavoriteItem>> = UiState.Loading,
        val count: Int = 0,
        val collected: Set<Long> = emptySet(),
        val busyRemove: Set<Long> = emptySet(),
        val busyCollect: Set<Long> = emptySet(),
        val expanded: Set<Long> = emptySet(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _navigate = Channel<String>(Channel.UNLIMITED)
    val navigate = _navigate.receiveAsFlow()

    private val _toast = Channel<String>(Channel.UNLIMITED)
    val toast = _toast.receiveAsFlow()

    private var started = false

    fun init() {
        if (started) return
        started = true
        viewModelScope.launch {
            runCatching { container.courses.ensure() }
                .onSuccess { list -> _state.update { it.copy(courses = list) } }
                .onFailure { e -> _toast.trySend(e.userMessage()) }
        }
        load()
    }

    fun setCourse(id: Long?) {
        _state.update { it.copy(courseId = id) }
        load()
    }

    fun retry() = load()

    private fun load() {
        _state.update { it.copy(items = UiState.Loading) }
        val courseId = _state.value.courseId
        viewModelScope.launch {
            runCatching { container.practice.favorites(courseId) }
                .onSuccess { list -> _state.update { it.copy(items = UiState.Ready(list)) } }
                .onFailure { e -> _state.update { it.copy(items = UiState.Error(e.userMessage())) } }
        }
        viewModelScope.launch {
            runCatching { container.practice.favoriteCount(courseId) }
                .onSuccess { c -> _state.update { it.copy(count = c.count) } }
        }
        viewModelScope.launch {
            runCatching { container.practice.collectedIds(courseId) }
                .onSuccess { ids -> _state.update { it.copy(collected = ids.toSet()) } }
        }
    }

    fun toggleExpand(questionId: Long) {
        _state.update { s ->
            s.copy(expanded = if (questionId in s.expanded) s.expanded - questionId else s.expanded + questionId)
        }
    }

    fun remove(questionId: Long) {
        if (questionId in _state.value.busyRemove) return
        _state.update { it.copy(busyRemove = it.busyRemove + questionId) }
        viewModelScope.launch {
            runCatching { container.practice.toggleFavorite(questionId) }
                .onSuccess { load() }
                .onFailure { e -> _toast.trySend(e.userMessage()) }
            _state.update { it.copy(busyRemove = it.busyRemove - questionId) }
        }
    }

    fun collect(item: FavoriteItem) {
        if (item.questionId in _state.value.busyCollect || item.questionId in _state.value.collected) return
        _state.update { it.copy(busyCollect = it.busyCollect + item.questionId) }
        viewModelScope.launch {
            runCatching { container.practice.collect(item.questionId) }
                .onSuccess {
                    _state.update { s -> s.copy(collected = s.collected + item.questionId) }
                    _toast.trySend("已收录错题本：「${item.stem.take(30)}…」，重练答对后自动出本")
                }
                .onFailure { e -> _toast.trySend(e.userMessage()) }
            _state.update { it.copy(busyCollect = it.busyCollect - item.questionId) }
        }
    }

    fun goRetrain() {
        _navigate.trySend(Routes.practice(mode = "favorite", courseId = _state.value.courseId))
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { FavoritesViewModel(container) }
        }
    }
}
