package com.zhixue.assistant.ui.screens.course

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhixue.assistant.AppContainer
import com.zhixue.assistant.core.userMessage
import com.zhixue.assistant.data.model.GraphDto
import com.zhixue.assistant.ui.components.BannerTone
import com.zhixue.assistant.ui.components.EmptyView
import com.zhixue.assistant.ui.components.ErrorState
import com.zhixue.assistant.ui.components.HintBanner
import com.zhixue.assistant.ui.components.InlineLoading
import com.zhixue.assistant.ui.components.KnowledgeGraphCanvas
import com.zhixue.assistant.ui.components.LoadingView
import com.zhixue.assistant.ui.components.LocalAppContainer
import com.zhixue.assistant.ui.components.LocalNavigator
import com.zhixue.assistant.ui.components.PageHeader
import com.zhixue.assistant.ui.components.PrimaryButton
import com.zhixue.assistant.ui.components.SecondaryButton
import com.zhixue.assistant.ui.nav.Routes
import com.zhixue.assistant.util.fmtDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 图谱节点「去答疑」需要同时带上课程与知识库，课程 id 由路由带入或按知识库反查。 */
data class GraphQuestion(val courseId: Long?, val kbId: Long, val ask: String, val rel: String)

/**
 * 知识图谱页：读取（可能从未生成 → null）与重新生成，stale 时提示资料已变更。
 * 画布交互（缩放/平移/点选）由 KnowledgeGraphCanvas 负责，这里只管数据与跳转。
 */
class GraphViewModel(private val container: AppContainer) : ViewModel(), MessageHost {

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { GraphViewModel(container) }
        }
    }

    data class State(
        val loading: Boolean = true,
        val error: String? = null,
        val graph: GraphDto? = null,
        val rebuilding: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _message = MutableStateFlow<UiMessage?>(null)
    override val message: StateFlow<UiMessage?> = _message.asStateFlow()
    private var seq = 0L

    private var kbId = 0L
    private var courseId: Long? = null

    override fun clearMessage() {
        _message.value = null
    }

    private fun emit(text: String, tone: BannerTone = BannerTone.Info) {
        seq += 1
        _message.value = UiMessage(seq, text, tone)
    }

    private inline fun update(block: State.() -> State) {
        _state.value = _state.value.block()
    }

    fun bind(kbId: Long, courseId: Long?) {
        if (this.kbId == kbId && this.courseId == courseId && _state.value.graph != null) return
        this.kbId = kbId
        this.courseId = courseId?.takeIf { it > 0L }
        _state.value = State()
        load()
    }

    fun load() {
        if (kbId <= 0L) return
        viewModelScope.launch {
            update { copy(loading = true, error = null) }
            val result = runCatching { container.course.graph(kbId) }
            result.onSuccess { update { copy(loading = false, error = null, graph = it) } }
                .onFailure { update { copy(loading = false, error = it.userMessage()) } }
        }
    }

    fun rebuild() {
        if (kbId <= 0L || _state.value.rebuilding) return
        viewModelScope.launch {
            update { copy(rebuilding = true, error = null) }
            val result = runCatching { container.course.rebuildGraph(kbId) }
            result.onSuccess {
                update { copy(rebuilding = false, graph = it, error = null) }
                emit("图谱已更新：${it.nodeCount} 个概念、${it.edgeCount} 条关系", BannerTone.Success)
            }.onFailure {
                update { copy(rebuilding = false) }
                emit(it.userMessage(), BannerTone.Error)
            }
        }
    }

    /**
     * 路由没带课程 id 时按知识库反查所属课程（课程列表量级很小，查到即停）；
     * 实在反查不到就退回当前可见的第一门课程，保证「去答疑」仍能带上可用的上下文。
     */
    suspend fun questionFor(name: String, related: List<String>): GraphQuestion? {
        if (kbId <= 0L) return null
        val resolved = courseId ?: reverseLookupCourseId()?.also { courseId = it }
        return GraphQuestion(
            courseId = resolved,
            kbId = kbId,
            ask = name,
            rel = related.take(4).joinToString(","),
        )
    }

    private suspend fun reverseLookupCourseId(): Long? {
        val courses = runCatching { container.courses.ensure() }.getOrNull().orEmpty()
        courses.forEach { course ->
            val kbs = runCatching { container.course.knowledgeBases(course.id) }.getOrNull().orEmpty()
            if (kbs.any { it.id == kbId }) return course.id
        }
        return courses.firstOrNull()?.id
    }
}

@Composable
fun GraphScreen(kbId: Long, courseId: Long = Routes.NO_COURSE) {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val vm: GraphViewModel = viewModel(factory = GraphViewModel.factory(container))
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(kbId, courseId) { vm.bind(kbId, courseId.takeIf { it > 0L }) }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .padding(horizontal = 14.dp)
                .padding(top = 12.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val graph = state.graph
            PageHeader(
                title = graph?.kbName?.takeIf { it.isNotBlank() }?.let { "$it · 知识图谱" } ?: "知识图谱",
                subtitle = if (graph != null) {
                    "${graph.nodeCount} 个概念 · ${graph.edgeCount} 条关系 · " +
                        (graph.updatedAt?.takeIf { it.isNotBlank() }?.let { "生成于 ${fmtDateTime(it)}" } ?: "尚未记录生成时间") +
                        "；双指缩放、拖动平移，点节点可发起答疑"
                } else {
                    "从本知识库资料里抽取概念与关系"
                },
                actions = {
                    SecondaryButton("刷新", onClick = { vm.load() }, enabled = !state.loading && !state.rebuilding)
                },
            )
            MessageChannel(vm)

            if (graph?.stale == true) {
                HintBanner("资料已变更，图谱需要重新生成", BannerTone.Warn)
            }

            PrimaryButton(
                text = when {
                    state.rebuilding -> "生成中…"
                    graph != null -> "重新生成"
                    else -> "生成图谱"
                },
                onClick = { vm.rebuild() },
                loading = state.rebuilding,
            )
        }

        Box(Modifier.weight(1f)) {
            when {
                state.loading -> LoadingView("正在读取图谱…", Modifier.fillMaxSize())

                state.error != null && state.graph == null -> ErrorState(
                    state.error.orEmpty(),
                    onRetry = { vm.load() },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                state.graph == null -> EmptyView(
                    title = "尚未生成图谱",
                    hint = "生成需要通读该知识库的全部片段，约十几秒；资料为空时不会有任何节点",
                    actionText = "生成图谱",
                    onAction = { vm.rebuild() },
                )

                else -> {
                    val graph = state.graph ?: return@Box
                    if (state.rebuilding) {
                        Box(Modifier.fillMaxSize().padding(bottom = 6.dp)) {
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                                InlineLoading("正在通读资料、抽取概念与关系…（约十几秒）")
                            }
                        }
                    }
                    KnowledgeGraphCanvas(
                        graph = graph,
                        modifier = Modifier.fillMaxSize(),
                        onNodeClick = { name, related ->
                            scope.launch {
                                val question = vm.questionFor(name, related)
                                if (question == null) {
                                    navigator("/chat?kbId=$kbId&ask=${Routes.encode(name)}")
                                } else {
                                    navigator(
                                        "/chat?courseId=${question.courseId ?: Routes.NO_COURSE}" +
                                            "&kbId=${question.kbId}" +
                                            "&ask=${Routes.encode(question.ask)}" +
                                            "&rel=${Routes.encode(question.rel)}"
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}
