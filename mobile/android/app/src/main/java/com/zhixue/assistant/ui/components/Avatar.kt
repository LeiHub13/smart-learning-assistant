package com.zhixue.assistant.ui.components

import android.graphics.Bitmap as AndroidBitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhixue.assistant.ui.theme.LocalZhixuePalette
import com.zhixue.assistant.ui.theme.hallAvatarColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 头像与远端小图：后端 files 目录下的静态资源免鉴权，按当前后端地址解析后取图。
 * 不引第三方图片库，只做 60 张的内存缓存；加载失败或未设置头像时回落成首字母色块（与 Web 一致）。
 */
private val bitmapCache = LruCache<String, AndroidBitmap>(60)

@Composable
fun rememberRemoteBitmap(url: String?): AndroidBitmap? {
    val container = LocalAppContainer.current
    var bitmap by remember(url) { mutableStateOf(url?.let { bitmapCache.get(it) }) }
    LaunchedEffect(url) {
        if (url.isNullOrBlank()) return@LaunchedEffect
        bitmapCache.get(url)?.let { bitmap = it; return@LaunchedEffect }
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = container.api.download(url, authenticated = false)
                AndroidBitmapFactory.decode(bytes)
            }.getOrNull()
        }
        if (loaded != null) {
            bitmapCache.put(url, loaded)
            bitmap = loaded
        }
    }
    return bitmap
}

private object AndroidBitmapFactory {
    fun decode(bytes: ByteArray): AndroidBitmap? =
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

@Composable
fun UserAvatar(
    name: String,
    avatarPath: String?,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    userId: Long? = null,
    rounded: Boolean = true,
) {
    val palette = LocalZhixuePalette.current
    val container = LocalAppContainer.current
    val absoluteUrl = remember(avatarPath, container.server.baseUrl) {
        avatarPath?.takeIf { it.isNotBlank() }?.let { container.api.resolve(it) }
    }
    val bitmap = rememberRemoteBitmap(absoluteUrl)
    val shape = if (rounded) CircleShape else RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(if (bitmap == null) palette.soft else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(size),
                contentScale = ContentScale.Crop,
            )
        } else {
            val letter = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            val tint = userId?.let { hallAvatarColor(it) } ?: palette.muted
            Box(
                modifier = Modifier.size(size).background(tint.copy(alpha = 0.18f), shape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    letter,
                    color = tint,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = (size.value * 0.42).sp,
                )
            }
        }
    }
}
