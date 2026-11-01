package com.zhixue.assistant.ui.screens.course

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhixue.assistant.core.UiState
import com.zhixue.assistant.data.model.CourseCard
import com.zhixue.assistant.ui.components.ConfirmDialog
import com.zhixue.assistant.ui.components.EmptyView
import com.zhixue.assistant.ui.components.ErrorState
import com.zhixue.assistant.ui.components.LabeledField
import com.zhixue.assistant.ui.components.LocalAppContainer
import com.zhixue.assistant.ui.components.LoadingView
import com.zhixue.assistant.ui.components.LocalNavController
import com.zhixue.assistant.ui.components.LocalNavigator
import com.zhixue.assistant.ui.components.PageHeader
import com.zhixue.assistant.ui.components.PrimaryButton
import com.zhixue.assistant.ui.components.SecondaryButton
import com.zhixue.assistant.ui.components.SwitchRow
import com.zhixue.assistant.ui.components.ZhixueCard
import com.zhixue.assistant.ui.nav.Routes

@Composable
fun ManageScreen() {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val navController = LocalNavController.current
    val vm: CourseViewModel = viewModel(factory = CourseViewModel.factory(container))
    val state by vm.state.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    val creating by vm.creating.collectAsStateWithLifecycle()
    val busyId by vm.busyId.collectAsStateWithLifecycle()
    var deleteTarget by remember { mutableStateOf<CourseCard?>(null) }
    var leaveTarget by remember { mutableStateOf<CourseCard?>(null) }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .padding(horizontal = 14.dp)
                .padding(top = 12.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PageHeader("我的课程", "创建课程、上传资料构建知识库，供 AI 答疑检索")
            MessageChannel(vm)
        }

        Box(Modifier.weight(1f)) {
            when (val s = state) {
                UiState.Loading -> LoadingPane("加载课程中…")
                is UiState.Error -> ErrorState(
                    s.message,
                    onRetry = { vm.reload() },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                is UiState.Ready -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item { CreateCoursePane(vm, form, creating) }

                    if (s.data.isEmpty()) {
                        item {
                            EmptyView(
                                title = "还没有课程",
                                hint = "创建一门课程，再上传资料构建知识库，AI 答疑就能引用它",
                            )
                        }
                    } else {
                        items(s.data, key = { it.id }) { course ->
                            CourseCardPane(
                                course = course,
                                busy = busyId == course.id,
                                actionsEnabled = busyId == null,
                                onPractice = { navigator("/practice?courseId=${course.id}") },
                                onKnowledgeBase = { navController.navigate(Routes.courseDetail(course.id)) },
                                onToggleHub = { vm.toggleHub(course) },
                                onAskDelete = { deleteTarget = course },
                                onAskLeave = { leaveTarget = course },
                            )
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { course ->
        ConfirmDialog(
            title = "删除课程",
            body = "确定删除课程「${course.name}」吗？该课程的知识库、文档、题目、练习、考试、掌握度、笔记、报告、" +
                "学习计划与课程绑定会话会一并删除，且无法恢复。",
            confirmText = "确认删除",
            danger = true,
            onConfirm = {
                deleteTarget = null
                vm.deleteCourse(course)
            },
            onDismiss = { deleteTarget = null },
        )
    }

    leaveTarget?.let { course ->
        ConfirmDialog(
            title = "退出课程",
            body = "确定退出课程「${course.name}」吗？退出后该课程不再出现在「我的课程」里，" +
                "已产生的练习、笔记等数据仍保留；课程若仍在 Hub 中可随时重新加入。",
            confirmText = "确认退出",
            danger = true,
            onConfirm = {
                leaveTarget = null
                vm.leaveCourse(course)
            },
            onDismiss = { leaveTarget = null },
        )
    }
}

/** 首屏加载态：LoadingView 自带 fillMaxSize，放在有界 Box 里居中。 */
@Composable
private fun LoadingPane(text: String) {
    LoadingView(text, Modifier.fillMaxSize())
}

@Composable
private fun CreateCoursePane(vm: CourseViewModel, form: CourseViewModel.Form, creating: Boolean) {
    ZhixueCard(title = "创建课程", subtitle = "名称必填；勾选入驻 Hub 后其他人可以在课程 Hub 里加入") {
        LabeledField(
            label = "课程名称",
            value = form.name,
            onValueChange = vm::onNameChange,
            placeholder = "如：数据结构与算法",
            maxLength = 60,
        )
        LabeledField(
            label = "课程简介",
            value = form.description,
            onValueChange = vm::onDescriptionChange,
            placeholder = "一句话说明这门课覆盖的内容",
            singleLine = false,
            minLines = 2,
            maxLength = 200,
            modifier = Modifier.padding(top = 8.dp),
        )
        SwitchRow(
            label = "入驻课程 Hub",
            checked = form.inHub,
            onCheckedChange = vm::onInHubChange,
            description = "入驻后其他用户可在课程 Hub 搜索并加入",
            modifier = Modifier.padding(top = 4.dp),
        )
        PrimaryButton(
            text = "创建课程",
            onClick = { vm.create() },
            loading = creating,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun CourseCardPane(
    course: CourseCard,
    busy: Boolean,
    actionsEnabled: Boolean,
    onPractice: () -> Unit,
    onKnowledgeBase: () -> Unit,
    onToggleHub: () -> Unit,
    onAskDelete: () -> Unit,
    onAskLeave: () -> Unit,
) {
    CourseInfoCard(course = course, busy = busy) {
        SecondaryButton("去练习", onClick = onPractice, enabled = actionsEnabled)
        SecondaryButton("知识库", onClick = onKnowledgeBase, enabled = actionsEnabled)
        if (course.isCreator) {
            SecondaryButton(
                text = if (course.inHub == true) "移出 Hub" else "入驻 Hub",
                onClick = onToggleHub,
                enabled = actionsEnabled,
            )
            SecondaryButton("删除课程", onClick = onAskDelete, enabled = actionsEnabled, danger = true)
        } else {
            SecondaryButton("退出课程", onClick = onAskLeave, enabled = actionsEnabled)
        }
    }
}
