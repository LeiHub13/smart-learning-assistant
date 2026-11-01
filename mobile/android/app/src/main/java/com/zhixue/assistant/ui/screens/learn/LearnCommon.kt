package com.zhixue.assistant.ui.screens.learn

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhixue.assistant.core.UiState
import com.zhixue.assistant.data.model.ExamReportItem
import com.zhixue.assistant.data.model.PracticeDetailItem
import com.zhixue.assistant.data.model.QuestionDto
import com.zhixue.assistant.ui.components.InlineLoading
import com.zhixue.assistant.ui.components.LabeledField
import com.zhixue.assistant.ui.components.SecondaryButton
import com.zhixue.assistant.ui.components.SelectField
import com.zhixue.assistant.ui.components.TagBadge
import com.zhixue.assistant.ui.components.TagTone
import com.zhixue.assistant.ui.components.cardShape
import com.zhixue.assistant.data.model.CourseCard
import com.zhixue.assistant.ui.theme.LocalZhixuePalette
import com.zhixue.assistant.util.Option
import com.zhixue.assistant.util.Review
import com.zhixue.assistant.util.parseOptions
import com.zhixue.assistant.util.parseReview

/**
 * 练习/考试共用视图模型：把 PracticeDetailItem 与 ExamReportItem 归一成同一个结构，
 * 报告页、变式结果、成绩单用同一套渲染。
 */
data class GradedItem(
    val questionId: Long,
    val type: String,
    val stem: String,
    val options: String?,
    val correctAnswer: String?,
    val analysis: String?,
    val userAnswer: String?,
    val correct: Boolean?,
    val score: Int?,
    val review: String?,
    val kpName: String?,
    val favorited: Boolean,
)

fun gradedOf(item: PracticeDetailItem): GradedItem = GradedItem(
    questionId = item.q.id,
    type = item.q.type,
    stem = item.q.stem,
    options = item.q.options,
    correctAnswer = item.q.answer,
    analysis = item.q.analysis,
    userAnswer = item.pq.userAnswer,
    correct = item.pq.correct,
    score = item.pq.score,
    review = item.pq.review,
    kpName = item.pq.kpName ?: item.q.kpName,
    favorited = item.q.favorited == true,
)

fun gradedOf(item: ExamReportItem): GradedItem = GradedItem(
    questionId = item.question.id,
    type = item.question.type,
    stem = item.question.stem,
    options = item.question.options,
    correctAnswer = item.question.answer,
    analysis = item.question.analysis,
    userAnswer = item.answer.userAnswer,
    correct = item.answer.correct,
    score = item.answer.score,
    review = item.answer.review,
    kpName = item.answer.kpName ?: item.question.kpName,
    favorited = item.question.favorited == true,
)

fun gradedOf(q: QuestionDto, userAnswer: String?, correct: Boolean?, score: Int?, review: String?): GradedItem =
    GradedItem(
        questionId = q.id,
        type = q.type,
        stem = q.stem,
        options = q.options,
        correctAnswer = q.answer,
        analysis = q.analysis,
        userAnswer = userAnswer,
        correct = correct,
        score = score,
        review = review,
        kpName = q.kpName,
        favorited = q.favorited == true,
    )

/** UiState 安全取值：只有 Ready 才返回 data，避免泛型强转。 */
fun <T> UiState<T>.readyData(): T? = when (this) {
    is UiState.Ready -> data
    else -> null
}

/** 课程下拉；includeAll 时提供「全部课程」（null）。 */
@Composable
fun CourseSelect(
    courses: List<CourseCard>,
    selected: Long?,
    onSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "课程",
    includeAll: Boolean = false,
    enabled: Boolean = true,
) {
    val options = buildList {
        if (includeAll) add("" to "全部课程")
        courses.forEach { add(it.id.toString() to it.name) }
    }
    SelectField(
        label = label,
        options = options,
        selected = selected?.toString() ?: if (includeAll) "" else null,
        onSelect = { key -> onSelect(key.toLongOrNull() ?: if (key.isEmpty() && includeAll) null else selected) },
        modifier = modifier,
        enabled = enabled && options.isNotEmpty(),
        emptyLabel = if (options.isEmpty()) "暂无课程" else "请选择",
    )
}

/** 作答选项区：单选/判断=单选按钮组，多选=复选（按选项顺序拼接字母），问答=多行文本。 */
@Composable
fun AnswerEditor(
    type: String,
    optionsRaw: String?,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options: List<Option> = when (type) {
        "问答" -> emptyList()
        "判断" -> parseOptions(optionsRaw).ifEmpty { listOf(Option("对", "对"), Option("错", "错")) }
        else -> parseOptions(optionsRaw)
    }
    Column(modifier.fillMaxWidth()) {
        when (type) {
            "多选" -> options.forEach { o ->
                val key = o.key.take(1)
                Row(
                    Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = key.isNotEmpty() && value.contains(key),
                        onCheckedChange = { on -> onChange(toggleMultiKey(value, o.key, options, on)) },
                    )
                    Text("${o.key}. ${o.text}", style = MaterialTheme.typography.bodyMedium)
                }
            }

            "问答" -> LabeledField(
                label = "你的答案",
                value = value,
                onValueChange = onChange,
                placeholder = "请输入你的答案…",
                singleLine = false,
                minLines = 3,
            )

            else -> options.forEach { o ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 44.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = value == o.key, onClick = { onChange(o.key) })
                    Spacer(Modifier.height(0.dp))
                    Text(
                        "${o.key}. ${o.text}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }
}

/** 多选答案：按选项顺序拼接字母（与 Web 排序逻辑一致）。 */
fun toggleMultiKey(current: String, key: String, order: List<Option>, add: Boolean): String {
    val chars = current.map { it }.toMutableSet()
    val ch = key.firstOrNull() ?: return current
    if (add) chars.add(ch) else chars.remove(ch)
    return order.mapNotNull { it.key.firstOrNull() }.filter { chars.contains(it) }.joinToString("")
}

/** 错题/收藏选项红绿对比：正确项 okSoft，用户错选 badSoft（与 Web markOption 一致）。 */
enum class OptMark { None, Correct, Wrong }

fun markOption(key: String, answer: String?, userAnswer: String?): OptMark {
    val k = key.uppercase()
    val c = (answer ?: "").uppercase()
    val u = (userAnswer ?: "").uppercase()
    return when {
        k.isNotBlank() && c.contains(k) -> OptMark.Correct
        k.isNotBlank() && u.contains(k) -> OptMark.Wrong
        else -> OptMark.None
    }
}

@Composable
fun OptionsComparison(optionsRaw: String?, answer: String?, userAnswer: String?, modifier: Modifier = Modifier) {
    val options = parseOptions(optionsRaw)
    if (options.isEmpty()) return
    val palette = LocalZhixuePalette.current
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o ->
            val mark = markOption(o.key.take(1), answer, userAnswer)
            val bg = when (mark) {
                OptMark.Correct -> palette.okSoft
                OptMark.Wrong -> palette.badSoft
                OptMark.None -> palette.card
            }
            val fg = when (mark) {
                OptMark.Correct -> palette.success
                OptMark.Wrong -> palette.danger
                OptMark.None -> palette.muted
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(bg, RoundedCornerShape(8.dp))
                    .border(1.dp, palette.border, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(o.key, color = fg, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    o.text,
                    color = if (mark == OptMark.None) palette.muted else fg,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                when (mark) {
                    OptMark.Correct -> TagBadge("正确答案", TagTone.Success)
                    OptMark.Wrong -> TagBadge("你的答案", TagTone.Danger)
                    OptMark.None -> Unit
                }
            }
        }
    }
}

/** 「你的答案 / 正确答案」对比行（问答、判断题或选项之外的补充）。 */
@Composable
fun AnswerCompareRows(userAnswer: String?, answer: String?, modifier: Modifier = Modifier) {
    val palette = LocalZhixuePalette.current
    Column(
        modifier
            .fillMaxWidth()
            .border(1.dp, palette.border, RoundedCornerShape(8.dp))
            .background(palette.card, RoundedCornerShape(8.dp)),
    ) {
        if (!userAnswer.isNullOrBlank()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text("你的答案", color = palette.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp))
                Text(userAnswer, color = palette.danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(start = 8.dp))
            }
            Box01Divider()
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("正确答案", color = palette.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp))
            Text(answer?.takeIf { it.isNotBlank() } ?: "—", color = palette.success, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(start = 8.dp))
        }
    }
}

@Composable
private fun Box01Divider() {
    val palette = LocalZhixuePalette.current
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(palette.border))
}

/** 「查看解析」展开区。 */
@Composable
fun AnalysisExpandable(analysis: String?) {
    if (analysis.isNullOrBlank()) return
    val palette = LocalZhixuePalette.current
    var open by remember(analysis) { mutableStateOf(false) }
    SecondaryButton(if (open) "收起解析" else "查看解析", onClick = { open = !open })
    if (open) {
        Text(
            analysis,
            style = MaterialTheme.typography.bodySmall,
            color = palette.text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .background(palette.soft, RoundedCornerShape(8.dp))
                .border(1.dp, palette.border, RoundedCornerShape(8.dp))
                .padding(10.dp),
        )
    }
}

/** 问答题 AI 批改：summary + 命中/未命中评分点；纯文本 review 兜底直显。 */
@Composable
fun ReviewBlock(review: String?, modifier: Modifier = Modifier) {
    val parsed: Review? = parseReview(review)
    if (parsed == null) return
    val palette = LocalZhixuePalette.current
    Column(modifier.fillMaxWidth().padding(top = 6.dp)) {
        if (parsed.summary.isNotBlank()) {
            Text(
                "AI 点评：${parsed.summary}",
                style = MaterialTheme.typography.bodySmall,
                color = palette.primary,
                fontWeight = FontWeight.Medium,
            )
        } else if (parsed.points.isEmpty() && !review.isNullOrBlank()) {
            Text("AI 点评：$review", style = MaterialTheme.typography.bodySmall, color = palette.primary)
        }
        parsed.points.forEach { p ->
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.Top) {
                Text(
                    if (p.hit) "✓" else "✗",
                    color = if (p.hit) palette.success else palette.danger,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
                Text(
                    p.point,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                if (p.note.isNotBlank()) {
                    Text(p.note, style = MaterialTheme.typography.bodySmall, color = palette.muted, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** 判对/判错的柔和色块包裹（palette.okSoft / badSoft）。 */
@Composable
fun GradedSurface(correct: Boolean?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val palette = LocalZhixuePalette.current
    val bg = when (correct) {
        true -> palette.okSoft
        false -> palette.badSoft
        null -> palette.card
    }
    val line = when (correct) {
        true -> palette.okLine
        false -> palette.badLine
        null -> palette.border
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = cardShape,
        color = bg,
        border = androidx.compose.foundation.BorderStroke(1.dp, line),
    ) { content() }
}

/** 题目元信息标签行：类型/知识点/难度/得分结论。 */
@Composable
fun QuestionTagRow(
    type: String,
    kp: String? = null,
    difficulty: String? = null,
    correct: Boolean? = null,
    score: Int? = null,
    extra: List<Pair<String, TagTone>> = emptyList(),
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TagBadge(type, TagTone.Info)
        if (!kp.isNullOrBlank()) TagBadge(kp, TagTone.Neutral)
        if (!difficulty.isNullOrBlank()) TagBadge(difficulty, TagTone.Neutral)
        when (correct) {
            true -> TagBadge("正确 +${score ?: 0}", TagTone.Success)
            false -> TagBadge("错误", TagTone.Danger)
            null -> Unit
        }
        extra.forEach { (text, tone) -> TagBadge(text, tone) }
    }
}

/** 收藏 / 收录错题本 操作行。 */
@Composable
fun FavoriteCollectRow(
    favorited: Boolean,
    busyFavorite: Boolean,
    busyCollect: Boolean,
    collected: Boolean,
    onFavorite: () -> Unit,
    onCollect: () -> Unit,
    modifier: Modifier = Modifier,
    showCollect: Boolean = true,
) {
    Row(
        modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busyFavorite) {
            InlineLoading("处理中…")
        } else {
            SecondaryButton(
                text = if (favorited) "★ 已收藏" else "☆ 收藏",
                onClick = onFavorite,
                modifier = Modifier.defaultMinSize(minHeight = 44.dp),
            )
        }
        if (!showCollect) return@Row
        if (collected) {
            TagBadge("已收录错题本", TagTone.Success)
        } else if (busyCollect) {
            InlineLoading("收录中…")
        } else {
            SecondaryButton(
                text = "收录错题本",
                onClick = onCollect,
                modifier = Modifier.defaultMinSize(minHeight = 44.dp),
            )
        }
    }
}

/** 判分结果卡：题号/题干 + 结论标签 + 选项红绿对比 + 答案对比 + 解析 + AI 批改 + 收藏/收录。 */
@Composable
fun GradedQuestionCard(
    index: Int,
    item: GradedItem,
    busyFavorite: Boolean,
    busyCollect: Boolean,
    collected: Boolean,
    onFavorite: () -> Unit,
    onCollect: () -> Unit,
    showCollect: Boolean = true,
) {
    val palette = LocalZhixuePalette.current
    GradedSurface(correct = item.correct) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "${index + 1}. 【${item.type}】${item.stem}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            QuestionTagRow(type = item.type, kp = item.kpName, correct = item.correct, score = item.score)
            OptionsComparison(item.options, item.correctAnswer, item.userAnswer)
            AnswerCompareRows(item.userAnswer, item.correctAnswer)
            if (!item.analysis.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Row {
                    Text(
                        "解析：",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = palette.muted,
                    )
                    Text(item.analysis.orEmpty(), style = MaterialTheme.typography.bodySmall, color = palette.text)
                }
            }
            ReviewBlock(item.review)
            FavoriteCollectRow(
                favorited = item.favorited,
                busyFavorite = busyFavorite,
                busyCollect = busyCollect,
                collected = collected,
                onFavorite = onFavorite,
                onCollect = onCollect,
                showCollect = showCollect,
            )
        }
    }
}
