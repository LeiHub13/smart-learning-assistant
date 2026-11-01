package com.zhixue.assistant.util

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.zhixue.assistant.BuildConfig
import java.io.File

/**
 * 文件相关一律走 Android 规范做法：
 *  - 导出（xlsx/pdf/anki txt/md）：优先写系统「下载」目录（MediaStore，Android 10+ 免权限），
 *    低版本回落到 DownloadManager 不可用时直接缓存 + 分享面板。
 *  - 预览/打开：交给系统应用（PDF Viewer 等），不内嵌 WebView。
 */
object FileActions {

    data class Saved(val uri: Uri?, val message: String)

    fun saveToDownloads(context: Context, fileName: String, bytes: ByteArray, mime: String): Saved {
        val safeName = sanitize(fileName)
        return if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return Saved(null, "无法写入下载目录，请改用「分享」保存")
            resolver.openOutputStream(uri, "w")?.use { out -> out.write(bytes) } ?: run {
                resolver.delete(uri, null as android.os.Bundle?)
                return Saved(null, "保存失败，请重试")
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Saved(uri, "已保存到「下载」/$safeName")
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.filesDir
            val target = File(dir, safeName)
            target.writeBytes(bytes)
            Saved(FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", target), "已保存到应用目录：${target.absolutePath}")
        }
    }

    /** 分享/另存为：把字节落到缓存再通过 FileProvider 交给系统选择器。 */
    fun share(context: Context, fileName: String, bytes: ByteArray, mime: String) {
        val cacheDir = File(context.cacheDir, "shared").apply { mkdirs() }
        val target = File(cacheDir, sanitize(fileName))
        target.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", target)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "保存或分享 ${sanitize(fileName)}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** 用系统应用打开（PDF、图片等）。 */
    fun openWithSystemApp(context: Context, fileName: String, bytes: ByteArray, mime: String): Boolean {
        val cacheDir = File(context.cacheDir, "shared").apply { mkdirs() }
        val target = File(cacheDir, sanitize(fileName))
        target.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", target)
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            true
        }.getOrDefault(false)
    }

    /** 第三方支付页/文档原文：调用系统浏览器，符合「不内嵌 WebView」的要求。 */
    fun openInBrowser(context: Context, url: String): Boolean {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return false
        return runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.fold(onSuccess = { true }, onFailure = { false })
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._\\u4e00-\\u9fa5-]"), "_").take(120).ifBlank { "文件" }

    fun mimeFor(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "doc" -> "application/msword"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "md" -> "text/markdown"
        "txt" -> "text/plain"
        "json" -> "application/json"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }
}
