package com.zhixue.assistant.ui.screens.insight

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhixue.assistant.ui.components.BannerTone
import com.zhixue.assistant.ui.components.HintBanner
import com.zhixue.assistant.ui.components.InlineLoading
import com.zhixue.assistant.ui.components.LabeledField
import com.zhixue.assistant.ui.components.LocalAppContainer
import com.zhixue.assistant.ui.components.LocalNavController
import com.zhixue.assistant.ui.components.LocalToast
import com.zhixue.assistant.ui.components.PageHeader
import com.zhixue.assistant.ui.components.PrimaryButton
import com.zhixue.assistant.ui.components.SecondaryButton
import com.zhixue.assistant.ui.components.SelectField
import com.zhixue.assistant.ui.components.ZhixueCard

@Composable
fun NoteEditorScreen(noteId: Long) {
    val container = LocalAppContainer.current
    val vm: NoteEditorViewModel = viewModel(factory = NoteEditorViewModel.factory(container))
    val courses by vm.courses.collectAsState()
    val courseKey by vm.courseKey.collectAsState()
    val kpName by vm.kpName.collectAsState()
    val title by vm.title.collectAsState()
    val content by vm.content.collectAsState()
    val loading by vm.loading.collectAsState()
    val saving by vm.saving.collectAsState()
    val message by vm.message.collectAsState()
    val saved by vm.saved.collectAsState()
    val toast = LocalToast.current
    val navController = LocalNavController.current

    LaunchedEffect(noteId) { vm.load(noteId) }
    LaunchedEffect(saved) {
        if (saved) {
            toast(if (noteId > 0) "笔记已保存" else "笔记已创建")
            navController.popBackStack()
        }
    }

    val editing = noteId > 0
    val options = remember(courses, courseKey, editing) {
        val base = courseOptions(courses)
        val key = courseKey
        val extra: List<Pair<String, String>> = when {
            !editing -> emptyList()
            key == null -> listOf(NO_COURSE_KEY to "未绑定课程")
            base.none { it.first == key } -> listOf(key to "原课程 #$key")
            else -> emptyList()
        }
        base + extra
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageHeader(
            title = if (editing) "编辑笔记" else "新建笔记",
            subtitle = if (editing) "编辑内容时不能更换所属课程（后端更新接口不接受 courseId）" else "笔记必须归属于一门课程",
        )

        val banner = message
        if (banner != null) {
            HintBanner(
                text = banner.text,
                tone = if (banner.error) BannerTone.Error else BannerTone.Success,
                onDismiss = { vm.dismissMessage() },
            )
        }

        if (loading) {
            InlineLoading("正在读取笔记…")
        }

        ZhixueCard(title = "笔记内容") {
            SelectField(
                label = "课程",
                options = options,
                selected = courseKey ?: NO_COURSE_KEY,
                onSelect = { vm.setCourse(it) },
                enabled = !editing && courses.isNotEmpty(),
                emptyLabel = if (courses.isEmpty()) "暂无课程" else "请选择",
            )
            if (!editing && courses.isEmpty()) {
                Text(
                    "还没有课程，先到「我的课程」创建课程后才能保存笔记。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            LabeledField(
                label = "知识点（可选）",
                value = kpName,
                onValueChange = { vm.setKpName(it) },
                placeholder = "如：HashMap 原理",
            )
            Spacer(Modifier.height(10.dp))
            LabeledField(
                label = "标题",
                value = title,
                onValueChange = { vm.setTitle(it) },
                placeholder = "一句话概括",
                maxLength = NOTE_TITLE_MAX,
                helper = "${title.length}/$NOTE_TITLE_MAX 字",
            )
            Spacer(Modifier.height(10.dp))
            LabeledField(
                label = "正文（支持 Markdown）",
                value = content,
                onValueChange = { vm.setContent(it) },
                placeholder = "记录学习/练习中的收获、易错点、思路…",
                singleLine = false,
                minLines = 8,
                maxLength = NOTE_CONTENT_MAX,
                helper = "${content.length}/$NOTE_CONTENT_MAX 字",
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(
                text = if (editing) "保存修改" else "保存笔记",
                onClick = { vm.save() },
                loading = saving,
                enabled = if (editing) true else courses.isNotEmpty(),
            )
            SecondaryButton("取消", onClick = { navController.popBackStack() })
        }
    }
}
