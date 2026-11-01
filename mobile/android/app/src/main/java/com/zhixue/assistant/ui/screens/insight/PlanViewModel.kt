package com.zhixue.assistant.ui.screens.insight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhixue.assistant.AppContainer
import com.zhixue.assistant.core.UiState
import com.zhixue.assistant.core.userMessage
import com.zhixue.assistant.data.model.CalendarCell
import com.zhixue.assistant.data.model.CourseCard
import com.zhixue.assistant.data.model.PlanDetail
import com.zhixue.assistant.data.model.PlanDto
import com.zhixue.assistant.data.model.PlanTaskDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** 同一天的任务归到一组（后端按计划起始日推算 taskDate）。 */
data class PlanTaskGroup(
    val dayNo: Int?,
    val taskDate: String?,
    val tasks: List<PlanTaskDto>,
)

data class PlanData(
    val courses: List<CourseCard>,
    val plans: List<PlanDto>,
    val openPlanId: Long?,
    val details: Map<Long, PlanDetail>,
    val month: String,
    val calendar: List<CalendarCell>,
    val calendarError: String?,
)

private val MONTH_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

fun currentMonthKey(): String = YearMonth.now().format(MONTH_FORMAT)

fun monthKeyOf(text: String): YearMonth? = runCatching { YearMonth.parse(text, MONTH_FORMAT) }.getOrNull()

fun nextMonthKey(text: String, delta: Int): String? =
    monthKeyOf(text)?.plusMonths(delta.toLong())?.format(MONTH_FORMAT)

class PlanViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow<UiState<PlanData>>(UiState.Loading)
    val state: StateFlow<UiState<PlanData>> = _state.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** 打卡是切换语义，锁住在途任务防止连点变成「打卡又取消」。 */
    private val _checkingTaskIds = MutableStateFlow<Set<Long>>(emptySet())
    val checkingTaskIds: StateFlow<Set<Long>> = _checkingTaskIds.asStateFlow()

    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message.asStateFlow()

    private val _goal = MutableStateFlow("")
    val goal: StateFlow<String> = _goal.asStateFlow()

    private val _days = MutableStateFlow("7")
    val days: StateFlow<String> = _days.asStateFlow()

    private val _courseKey = MutableStateFlow<String?>(null)
    val courseKey: StateFlow<String?> = _courseKey.asStateFlow()

    private var calendarMonth: String = currentMonthKey()

    init {
        load()
    }

    fun setGoal(value: String) {
        _goal.value = value
    }

    fun setDays(value: String) {
        _days.value = value.filter { it.isDigit() }.take(2)
    }

    fun setCourse(key: String?) {
        _courseKey.value = key
    }

    fun dismissMessage() {
        _message.value = null
    }

    /** 天数超出 1..30 时后端按 7 天处理，这里同样兜底，避免用户以为生效了别的值。 */
    private fun daysOrDefault(): Int = _days.value.toIntOrNull()?.takeIf { it in 1..30 } ?: 7

    fun load(expandPlanId: Long? = null) {
        viewModelScope.launch {
            if (_state.value !is UiState.Ready) _state.value = UiState.Loading
            val courses = runCatching { container.courses.ensure() }.getOrElse {
                _state.value = UiState.Error(it.userMessage())
                return@launch
            }
            val loadedPlans = runCatching { container.study.plans() }.getOrElse {
                _state.value = UiState.Error(it.userMessage())
                return@launch
            }
            if (_courseKey.value == null) {
                _courseKey.value = courses.firstOrNull()?.id?.toString()
            }
            val previous = (_state.value as? UiState.Ready)?.data
            val openId = expandPlanId ?: previous?.openPlanId ?: loadedPlans.firstOrNull()?.id
            val details = previous?.details?.toMutableMap() ?: mutableMapOf()
            if (openId != null && details[openId] == null) {
                runCatching { container.study.plan(openId) }
                    .onSuccess { details[openId] = it }
                    .onFailure { _message.value = UiMessage("计划详情加载失败：${it.userMessage()}", error = true) }
            }
            val calendar = runCatching { container.study.calendar(calendarMonth) }
            _state.value = UiState.Ready(
                PlanData(
                    courses = courses,
                    plans = loadedPlans,
                    openPlanId = openId,
                    details = details.toMap(),
                    month = calendarMonth,
                    calendar = calendar.getOrDefault(previous?.calendar ?: emptyList()),
                    calendarError = calendar.exceptionOrNull()?.userMessage(),
                )
            )
        }
    }

    fun shiftMonth(delta: Int) {
        val next = nextMonthKey(calendarMonth, delta) ?: return
        calendarMonth = next
        viewModelScope.launch {
            val data = (_state.value as? UiState.Ready)?.data ?: return@launch
            val calendar = runCatching { container.study.calendar(next) }
            _state.value = UiState.Ready(
                data.copy(
                    month = next,
                    calendar = calendar.getOrDefault(data.calendar),
                    calendarError = calendar.exceptionOrNull()?.userMessage(),
                )
            )
        }
    }

    fun toggle(planId: Long) {
        viewModelScope.launch {
            val data = (_state.value as? UiState.Ready)?.data ?: return@launch
            if (data.openPlanId == planId) {
                _state.value = UiState.Ready(data.copy(openPlanId = null))
                return@launch
            }
            val detail = data.details[planId] ?: runCatching { container.study.plan(planId) }.getOrElse {
                _message.value = UiMessage("计划详情加载失败：${it.userMessage()}", error = true)
                return@launch
            }
            _state.value = UiState.Ready(data.copy(openPlanId = planId, details = data.details + (planId to detail)))
        }
    }

    fun createPlan() {
        if (_busy.value) return
        val goal = _goal.value.trim()
        if (goal.isEmpty()) {
            _message.value = UiMessage("学习目标不能为空", error = true)
            return
        }
        val courseId = _courseKey.value.toCourseIdOrNull()
        val days = daysOrDefault()
        _busy.value = true
        _message.value = null
        viewModelScope.launch {
            val created = runCatching { container.study.createPlan(courseId, goal, days) }.getOrElse {
                _message.value = UiMessage("计划生成失败：${it.userMessage()}", error = true)
                _busy.value = false
                return@launch
            }
            _goal.value = ""
            _busy.value = false
            // LLM 生成后重拉列表与日历，并直接展开新计划
            load(expandPlanId = created.id)
        }
    }

    fun deletePlan(planId: Long) {
        viewModelScope.launch {
            runCatching { container.study.deletePlan(planId) }
                .onFailure { _message.value = UiMessage("删除失败：${it.userMessage()}", error = true) }
            val data = (_state.value as? UiState.Ready)?.data
            if (data != null && data.openPlanId == planId) {
                _state.value = UiState.Ready(data.copy(openPlanId = null, details = data.details - planId))
            }
            load()
        }
    }

    fun checkIn(task: PlanTaskDto) {
        val planId = task.planId ?: return
        if (_checkingTaskIds.value.contains(task.id)) return
        _checkingTaskIds.value = _checkingTaskIds.value + task.id
        viewModelScope.launch {
            runCatching { container.study.checkIn(task.id) }
                .onSuccess {
                    val data = (_state.value as? UiState.Ready)?.data
                    if (data != null) {
                        val detail = runCatching { container.study.plan(planId) }.getOrNull()
                        val calendar = runCatching { container.study.calendar(data.month) }
                        _state.value = UiState.Ready(
                            data.copy(
                                details = if (detail != null) data.details + (planId to detail) else data.details,
                                plans = data.plans.map { if (it.id == planId) detail?.plan ?: it else it },
                                calendar = calendar.getOrDefault(data.calendar),
                                calendarError = calendar.exceptionOrNull()?.userMessage(),
                            )
                        )
                    }
                }
                .onFailure { _message.value = UiMessage("打卡失败：${it.userMessage()}", error = true) }
            _checkingTaskIds.value = _checkingTaskIds.value - task.id
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { PlanViewModel(container) }
        }

        /** 任务按日期升序分组，一天一组；组内按 dayNo 排序。 */
        fun groupTasks(tasks: List<PlanTaskDto>): List<PlanTaskGroup> =
            tasks.groupBy { (it.dayNo ?: Int.MAX_VALUE) to it.taskDate.orEmpty() }
                .entries
                .sortedWith(compareBy({ it.key.second }, { it.key.first }))
                .map { (_, group) ->
                    PlanTaskGroup(
                        dayNo = group.firstNotNullOfOrNull { it.dayNo },
                        taskDate = group.firstNotNullOfOrNull { it.taskDate },
                        tasks = group.sortedBy { it.dayNo ?: Int.MAX_VALUE },
                    )
                }
    }
}

/** 计划进度：已完成任务 / 总任务，与 Web 的 progress() 一致。 */
fun PlanDetail.progressPercent(): Int =
    if (tasks.isEmpty()) 0 else kotlin.math.round(doneCount * 100.0 / tasks.size).toInt()
