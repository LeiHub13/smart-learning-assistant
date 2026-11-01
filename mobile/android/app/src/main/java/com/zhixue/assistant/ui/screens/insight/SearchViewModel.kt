package com.zhixue.assistant.ui.screens.insight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhixue.assistant.AppContainer
import com.zhixue.assistant.core.UiState
import com.zhixue.assistant.core.userMessage
import com.zhixue.assistant.data.model.CourseCard
import com.zhixue.assistant.data.model.SearchResults
import com.zhixue.assistant.ui.nav.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 后端每组已经限量，这里再兜一次，保证与 Web 的展示条数一致。 */
const val SEARCH_QUESTION_LIMIT = 5
const val SEARCH_NOTE_LIMIT = 5
const val SEARCH_EXAM_LIMIT = 3
const val SEARCH_DOC_LIMIT = 5

class SearchViewModel(private val container: AppContainer) : ViewModel() {

    private val _keyword = MutableStateFlow("")
    val keyword: StateFlow<String> = _keyword.asStateFlow()

    private val _courseKey = MutableStateFlow(Routes.NO_COURSE.toString())
    val courseKey: StateFlow<String> = _courseKey.asStateFlow()

    private val _courses = MutableStateFlow<List<CourseCard>>(emptyList())
    val courses: StateFlow<List<CourseCard>> = _courses.asStateFlow()

    /** null = 还没有执行过搜索，用于区分「空状态」和「结果空」。 */
    private val _result = MutableStateFlow<UiState<SearchResults>?>(null)
    val result: StateFlow<UiState<SearchResults>?> = _result.asStateFlow()

    private val _executedKeyword = MutableStateFlow("")
    val executedKeyword: StateFlow<String> = _executedKeyword.asStateFlow()

    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message.asStateFlow()

    init {
        viewModelScope.launch {
            _courses.value = runCatching { container.courses.ensure() }.getOrDefault(emptyList())
        }
    }

    fun setKeyword(value: String) {
        _keyword.value = value
    }

    fun setCourse(key: String?) {
        _courseKey.value = key ?: Routes.NO_COURSE.toString()
    }

    fun dismissMessage() {
        _message.value = null
    }

    fun search() {
        val keyword = _keyword.value.trim()
        if (keyword.isEmpty()) {
            _message.value = UiMessage("搜索词不能为空", error = true)
            return
        }
        _message.value = null
        _result.value = UiState.Loading
        viewModelScope.launch {
            val courseId = _courseKey.value.toLongOrNull()?.takeIf { it > 0 }
            runCatching { container.study.search(keyword, courseId) }
                .onSuccess {
                    _executedKeyword.value = keyword
                    _result.value = UiState.Ready(it)
                }
                .onFailure { _result.value = UiState.Error(it.userMessage()) }
        }
    }

    /** 从路由带进来的关键词（顶栏搜索图标会跳 search?q=…）。 */
    fun searchFromRoute(query: String) {
        if (query.isBlank()) return
        _keyword.value = query
        search()
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { SearchViewModel(container) }
        }
    }
}
