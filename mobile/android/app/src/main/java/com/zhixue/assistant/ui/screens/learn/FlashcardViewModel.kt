package com.zhixue.assistant.ui.screens.learn

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhixue.assistant.AppContainer
import com.zhixue.assistant.core.userMessage
import com.zhixue.assistant.data.model.DocListItemDto
import com.zhixue.assistant.data.model.FlashcardDto
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 闪卡：到期优先学习队列 + 翻卡两档自评（ok/miss）+ 生成/删除/导出 Anki。 */
class FlashcardViewModel(private val container: AppContainer) : ViewModel() {

    data class State(
        val queue: List<FlashcardDto> = emptyList(),
        val idx: Int = 0,
        val revealed: Boolean = false,
        val loading: Boolean = false,
        val error: String? = null,
        val due: Int = 0,
        val total: Int = 0,
        val lastHint: String? = null,
        val finished: Boolean = false,
        val docs: List<DocListItemDto> = emptyList(),
        val docId: Long? = null,
        val generating: Boolean = false,
        val grading: Boolean = false,
        val confirmDelete: Boolean = false,
        val exportTick: Int = 0,
    ) {
        val current: FlashcardDto? get() = queue.getOrNull(idx)
        val canExport: Boolean get() = total > 0
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _toast = Channel<String>(Channel.UNLIMITED)
    val toast = _toast.receiveAsFlow()

    private var started = false

    fun init() {
        if (started) return
        started = true
        loadQueue()
        loadStats()
        loadDocs()
    }

    fun loadQueue() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { container.flashcard.study(courseId = null, count = 10) }
                .onSuccess { list ->
                    _state.update {
                        it.copy(
                            queue = list,
                            idx = 0,
                            revealed = false,
                            finished = false,
                            lastHint = null,
                            loading = false,
                            error = null,
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.userMessage()) } }
        }
    }

    private fun loadStats() {
        viewModelScope.launch {
            runCatching { container.flashcard.due() }.onSuccess { d ->
                _state.update { it.copy(due = d.due) }
            }
        }
        viewModelScope.launch {
            runCatching { container.flashcard.count() }.onSuccess { c ->
                _state.update { it.copy(total = c.toInt()) }
            }
        }
    }

    private fun loadDocs() {
        viewModelScope.launch {
            runCatching { container.course.allDocuments() }
                .onSuccess { docs -> _state.update { it.copy(docs = docs) } }
        }
    }

    fun reveal() = _state.update { it.copy(revealed = true) }

    fun setDocId(id: Long?) = _state.update { it.copy(docId = id) }

    fun grade(result: String) {
        val card = _state.value.current ?: return
        if (_state.value.grading) return
        _state.update { it.copy(grading = true) }
        viewModelScope.launch {
            runCatching { container.flashcard.review(card.id, result) }
                .onSuccess { updated ->
                    _state.update { s ->
                        val nextQueue = s.queue.filterNot { it.id == card.id }
                        val nextIdx = if (s.idx >= nextQueue.size) nextQueue.size else s.idx
                        s.copy(
                            queue = nextQueue,
                            idx = nextIdx,
                            revealed = false,
                            grading = false,
                            finished = nextQueue.isEmpty(),
                            lastHint = if (result == "ok") {
                                "已排期：盒 ${updated.box}，下次复习 ${com.zhixue.assistant.util.friendlyTime(updated.dueAt)}"
                            } else {
                                "已排期：约 10 分钟后重现（盒 ${updated.box}），下次复习 ${com.zhixue.assistant.util.friendlyTime(updated.dueAt)}"
                            },
                        )
                    }
                    loadStats()
                }
                .onFailure { e ->
                    _state.update { it.copy(grading = false) }
                    _toast.trySend(e.userMessage())
                }
        }
    }

    fun generate(mode: String) {
        if (_state.value.generating) return
        if (mode == "doc" && _state.value.docId == null) return
        _state.update { it.copy(generating = true) }
        viewModelScope.launch {
            runCatching { container.flashcard.generate(mode, _state.value.docId) }
                .onSuccess { r ->
                    _toast.trySend("已生成 ${r.created} 张闪卡")
                    _state.update { it.copy(generating = false) }
                    loadQueue()
                    loadStats()
                }
                .onFailure { e ->
                    // 后端原文直接展示（「错题本是空的…」「会话内容太少…」）
                    _state.update { it.copy(generating = false, error = e.userMessage()) }
                }
        }
    }

    fun requestDelete() = _state.update { it.copy(confirmDelete = true) }
    fun cancelDelete() = _state.update { it.copy(confirmDelete = false) }

    fun confirmDelete() {
        val card = _state.value.current ?: return
        _state.update { it.copy(confirmDelete = false) }
        viewModelScope.launch {
            runCatching { container.flashcard.delete(card.id) }
                .onSuccess {
                    _toast.trySend("已删除该闪卡")
                    loadQueue()
                    loadStats()
                }
                .onFailure { e -> _toast.trySend(e.userMessage()) }
        }
    }

    fun requestExport() = _state.update { it.copy(exportTick = it.exportTick + 1) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun progressText(): String {
        val s = _state.value
        if (s.queue.isEmpty()) return ""
        return "第 ${minOf(s.idx + 1, s.queue.size)} / ${s.queue.size} 张"
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { FlashcardViewModel(container) }
        }
    }
}
