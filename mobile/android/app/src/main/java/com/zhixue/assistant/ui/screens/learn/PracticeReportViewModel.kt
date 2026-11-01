package com.zhixue.assistant.ui.screens.learn

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhixue.assistant.AppContainer
import com.zhixue.assistant.core.UiState
import com.zhixue.assistant.core.userMessage
import com.zhixue.assistant.data.model.PracticeDetail
import com.zhixue.assistant.ui.nav.Routes
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 练习报告：逐题判分/解析/AI 批改 + 收藏 + 收录错题本 + 一键重练入口。 */
class PracticeReportViewModel(private val container: AppContainer) : ViewModel() {

    data class State(
        val detail: UiState<PracticeDetail> = UiState.Loading,
        val items: List<GradedItem> = emptyList(),
        val busyFavorite: Set<Long> = emptySet(),
        val busyCollect: Set<Long> = emptySet(),
        val collected: Set<Long> = emptySet(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _navigate = Channel<String>(Channel.UNLIMITED)
    val navigate = _navigate.receiveAsFlow()

    private val _toast = Channel<String>(Channel.UNLIMITED)
    val toast = _toast.receiveAsFlow()

    private var loadedId: Long? = null

    fun init(practiceId: Long) {
        if (loadedId == practiceId) return
        loadedId = practiceId
        load()
    }

    fun load() {
        val id = loadedId ?: return
        _state.update { it.copy(detail = UiState.Loading) }
        viewModelScope.launch {
            runCatching { container.practice.detail(id) }
                .onSuccess { detail ->
                    _state.update {
                        it.copy(
                            detail = UiState.Ready(detail),
                            items = detail.items.map { item -> gradedOf(item) },
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(detail = UiState.Error(e.userMessage())) } }
        }
    }

    private fun updateItem(questionId: Long, transform: (GradedItem) -> GradedItem) {
        _state.update { s -> s.copy(items = s.items.map { if (it.questionId == questionId) transform(it) else it }) }
    }

    fun toggleFavorite(questionId: Long) {
        if (questionId in _state.value.busyFavorite) return
        _state.update { it.copy(busyFavorite = it.busyFavorite + questionId) }
        viewModelScope.launch {
            runCatching { container.practice.toggleFavorite(questionId) }
                .onSuccess { r ->
                    updateItem(questionId) { it.copy(favorited = r.favorited) }
                    _toast.trySend(if (r.favorited) "已收藏" else "已取消收藏")
                }
                .onFailure { e -> _toast.trySend(e.userMessage()) }
            _state.update { it.copy(busyFavorite = it.busyFavorite - questionId) }
        }
    }

    fun collect(questionId: Long, stem: String) {
        if (questionId in _state.value.busyCollect || questionId in _state.value.collected) return
        _state.update { it.copy(busyCollect = it.busyCollect + questionId) }
        viewModelScope.launch {
            runCatching { container.practice.collect(questionId) }
                .onSuccess {
                    _state.update { s -> s.copy(collected = s.collected + questionId) }
                    _toast.trySend("已收录错题本：「${stem.take(30)}…」，重练答对后自动出本")
                }
                .onFailure { e -> _toast.trySend(e.userMessage()) }
            _state.update { it.copy(busyCollect = it.busyCollect - questionId) }
        }
    }

    fun retrainMistakes() {
        val courseId = _state.value.detail.readyData()?.courseId ?: return
        _navigate.trySend(Routes.practice(mode = "mistake", courseId = courseId))
    }

    fun retrainFavorites() {
        val courseId = _state.value.detail.readyData()?.courseId ?: return
        _navigate.trySend(Routes.practice(mode = "favorite", courseId = courseId))
    }

    fun again() {
        val courseId = _state.value.detail.readyData()?.courseId ?: return
        _navigate.trySend(Routes.practice(mode = "normal", courseId = courseId))
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { PracticeReportViewModel(container) }
        }
    }
}
