package com.zhixue.assistant.ui.screens.course

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhixue.assistant.AppContainer
import com.zhixue.assistant.core.userMessage
import com.zhixue.assistant.data.model.CourseCard
import com.zhixue.assistant.ui.components.BannerTone
import com.zhixue.assistant.ui.components.HintBanner
import com.zhixue.assistant.ui.components.InlineLoading
import com.zhixue.assistant.ui.components.LocalToast
import com.zhixue.assistant.ui.components.TagBadge
import com.zhixue.assistant.ui.components.TagTone
import com.zhixue.assistant.ui.components.ZhixueCard
import com.zhixue.assistant.ui.theme.LocalZhixuePalette
import com.zhixue.assistant.util.FileActions
import com.zhixue.assistant.util.humanizeSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** 单文件上传上限，与 ai-service 解析侧的约定一致，前端先拦一次。 */
const val MAX_UPLOAD_BYTES: Long = 20L * 1024 * 1024

/** 文档管理页课程下拉里的「不限课程」选项。 */
const val ALL_COURSES_LABEL = "全部课程"

/** SAF 文件选择器的 MIME 白名单：pdf、docx、各类文本。 */
val UPLOAD_MIME_TYPES = arrayOf(
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "text/*",
)

/** ai-service 能解析的扩展名（旧版 .doc 明确不支持）。 */
private val SUPPORTED_TEXT_EXTENSIONS = setOf("txt", "md", "markdown", "java", "json", "xml", "yml", "yaml", "sql")
private val SUPPORTED_BINARY_MIME = mapOf(
    "pdf" to "application/pdf",
    "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
)

const val LEGACY_DOC_HINT = "不支持 .doc 旧版 Word 格式，请在 Word 中另存为 .docx 后重新上传"

private val SUPPORTED_LIST = "txt / md / markdown / java / json / xml / yml / yaml / sql / pdf / docx"

fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

fun isSupportedExtension(ext: String): Boolean = ext in SUPPORTED_TEXT_EXTENSIONS || ext in SUPPORTED_BINARY_MIME

fun uploadMimeFor(ext: String): String = SUPPORTED_BINARY_MIME[ext] ?: "text/plain"

/** 粘贴文本入库时补齐扩展名：无扩展名按 .txt，旧版 .doc 直接拒绝。 */
fun normalizedTextName(rawName: String): String {
    val base = rawName.trim().ifBlank { "未命名文档" }
    val ext = extensionOf(base)
    return when {
        ext.isEmpty() -> "$base.txt"
        ext == "doc" -> base
        isSupportedExtension(ext) -> base
        else -> "$base.txt"
    }
}

sealed interface PickedDocument {
    data class Ready(val fileName: String, val bytes: ByteArray, val mime: String) : PickedDocument
    data class Rejected(val reason: String) : PickedDocument
}

private fun queryColumn(context: Context, uri: Uri, column: String): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
    }
}.getOrNull()

/**
 * 读取系统文件选择器选中的文档：先按扩展名与体积拦住明显不合规的，再走 contentResolver 取字节。
 * 二进制（pdf/docx）原样上传交给后端解析，绝不做文本解码，避免字节被破坏。
 */
suspend fun readPickedDocument(context: Context, uri: Uri): PickedDocument = withContext(Dispatchers.IO) {
    val fileName = queryColumn(context, uri, OpenableColumns.DISPLAY_NAME)
        ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty()
    val name = fileName.ifBlank { "未命名文档" }
    val ext = extensionOf(name)
    if (ext == "doc") return@withContext PickedDocument.Rejected(LEGACY_DOC_HINT)
    if (!isSupportedExtension(ext)) {
        return@withContext PickedDocument.Rejected("不支持 .$ext 文件，可选：$SUPPORTED_LIST")
    }
    val declaredSize = queryColumn(context, uri, OpenableColumns.SIZE)?.toLongOrNull()
    if (declaredSize != null && declaredSize > MAX_UPLOAD_BYTES) {
        return@withContext PickedDocument.Rejected("单个文件不能超过 20MB（当前 ${humanizeSize(declaredSize)}）")
    }
    val bytes = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
    }.getOrElse { return@withContext PickedDocument.Rejected("读取文件失败：${it.userMessage()}") }
    if (bytes.isEmpty()) return@withContext PickedDocument.Rejected("文件内容为空或无法读取，请换个文件再试")
    if (bytes.size > MAX_UPLOAD_BYTES) {
        return@withContext PickedDocument.Rejected("单个文件不能超过 20MB（当前 ${humanizeSize(bytes.size.toLong())}）")
    }
    PickedDocument.Ready(name, bytes, uploadMimeFor(ext))
}

/** VM 的一次性提示：Info/Success 走 snackbar，Error/Warn 留在页面上直到用户关闭。 */
data class UiMessage(val seq: Long, val text: String, val tone: BannerTone)

interface MessageHost {
    val message: StateFlow<UiMessage?>
    fun clearMessage()
}

@Composable
fun MessageChannel(host: MessageHost, modifier: Modifier = Modifier) {
    val toast = LocalToast.current
    val message by host.message.collectAsState()
    LaunchedEffect(message?.seq) {
        val current = message ?: return@LaunchedEffect
        if (current.tone == BannerTone.Info || current.tone == BannerTone.Success) {
            toast(current.text)
            host.clearMessage()
        }
    }
    val visible = message
    if (visible != null && (visible.tone == BannerTone.Error || visible.tone == BannerTone.Warn)) {
        HintBanner(visible.text, visible.tone, onDismiss = host::clearMessage, modifier = modifier)
    }
}

/** 与 Web CourseCard 同构的课程卡：角色标签 + 简介 + 统计行 + 操作区。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CourseInfoCard(
    course: CourseCard,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    actions: @Composable FlowRowScope.() -> Unit = {},
) {
    val palette = LocalZhixuePalette.current
    ZhixueCard(modifier) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TagBadge("课程")
            when {
                course.isCreator -> TagBadge("我创建的", TagTone.Success)
                course.enrolled == true -> TagBadge("我加入的")
            }
            if (course.inHub == true) TagBadge("已入驻 Hub", TagTone.Info)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            course.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = palette.primaryDeep,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            course.description?.takeIf { it.isNotBlank() } ?: "暂无简介",
            style = MaterialTheme.typography.bodySmall,
            color = palette.muted,
        )
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = palette.border)
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            course.ownerName?.takeIf { it.isNotBlank() }?.let {
                Text("创建者 $it", style = MaterialTheme.typography.bodySmall, color = palette.muted)
            }
            course.memberCount?.let {
                Text("$it 人加入", style = MaterialTheme.typography.bodySmall, color = palette.muted)
            }
            Text("知识库 ${course.kbCount ?: 0}", style = MaterialTheme.typography.bodySmall, color = palette.muted)
            Text("文档 ${course.docCount ?: 0}", style = MaterialTheme.typography.bodySmall, color = palette.muted)
            Text("片段 ${course.chunkCount ?: 0}", style = MaterialTheme.typography.bodySmall, color = palette.muted)
            Text("题目 ${course.questionCount ?: 0}", style = MaterialTheme.typography.bodySmall, color = palette.muted)
        }
        if (busy) {
            Spacer(Modifier.height(8.dp))
            InlineLoading("处理中…")
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = actions,
        )
    }
}

/** 小标签样式的内容块（文档正文、速览等），底色跟随主题。 */
@Composable
fun SoftPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalZhixuePalette.current
    Column(
        modifier
            .fillMaxWidth()
            .background(palette.soft, RoundedCornerShape(8.dp))
            .padding(12.dp),
        content = content,
    )
}

@Composable
fun MetaLine(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodySmall, color = LocalZhixuePalette.current.muted)
}

/** 卡片标题行里的紧凑操作（上传 / 图谱 / 删除知识库），比 OutlinedButton 更省横向空间。 */
@Composable
fun TextButtonCompact(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val palette = LocalZhixuePalette.current
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
    ) {
        Text(text, fontSize = 13.sp, color = if (danger) palette.danger else palette.primary, fontWeight = FontWeight.Medium)
    }
}

/** 下载目录写入 + 失败回落分享面板；所有分支都给出后端/系统层面的可读结果。 */
@Composable
fun rememberFileSaver(): (String, ByteArray, String) -> Unit {
    val context = LocalContext.current
    val toast = LocalToast.current
    val action: (String, ByteArray, String) -> Unit = remember(context, toast) {
        { fileName, bytes, mime ->
            val result = runCatching { FileActions.saveToDownloads(context, fileName, bytes, mime) }
            val saved = result.getOrNull()
            if (saved != null && saved.uri != null) {
                toast(saved.message)
            } else {
                val reason = result.exceptionOrNull()?.message ?: saved?.message ?: "无法写入下载目录"
                toast("$reason，已改用分享面板")
                runCatching { FileActions.share(context, fileName, bytes, mime) }
                    .onFailure { toast("分享失败：${it.userMessage()}") }
            }
        }
    }
    return action
}

/**
 * 「查看原文件」：PDF 取字节交给系统应用，失败回落系统浏览器；
 * 其它类型优先系统浏览器，打不开时下载后走分享面板。
 */
suspend fun openOriginalFile(
    context: Context,
    container: AppContainer,
    fileName: String,
    fileUrl: String,
    onMessage: (String) -> Unit,
) {
    val url = container.api.resolve(fileUrl)
    if (url.isBlank()) {
        onMessage("后端没有返回原文件地址")
        return
    }
    val mime = FileActions.mimeFor(fileName)
    if (mime == "application/pdf") {
        val bytes = runCatching { container.api.download(url, authenticated = false) }
            .getOrElse { onMessage(it.userMessage()); return }
        if (FileActions.openWithSystemApp(context, fileName, bytes, mime)) return
        if (FileActions.openInBrowser(context, url)) return
        onMessage("没有可打开 PDF 的应用，可先「下载文本副本」查看内容")
        return
    }
    if (FileActions.openInBrowser(context, url)) return
    val bytes = runCatching { container.api.download(url, authenticated = false) }
        .getOrElse { onMessage(it.userMessage()); return }
    runCatching { FileActions.share(context, fileName, bytes, mime) }
        .onFailure { onMessage(it.userMessage()) }
}

/** 讲义/文件名等需要落到下载目录时用的安全名（与 FileActions 的清洗规则一致的方向）。 */
fun sanitizeTopicName(raw: String): String =
    raw.trim().ifBlank { "未命名" }.replace(Regex("[\\\\/:*?\"<>|]"), "-").take(60)
