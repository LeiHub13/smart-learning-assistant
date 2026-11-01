package com.zhixue.assistant.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.zhixue.assistant.ui.theme.LocalZhixuePalette

/**
 * Android 16(API 36) 起，访问局域网地址需要「本地网络」权限：没有它时连接会被系统直接丢弃，
 * 客户端只能看到超时——很容易被误判成后端没启动。这里在检测到当前后端是局域网/模拟器宿主地址、
 * 且权限尚未授予时，明确提示并提供一键授权（被拒后仍可去系统设置里打开）。
 */
@Composable
fun LocalNetworkGate(modifier: Modifier = Modifier) {
    if (Build.VERSION.SDK_INT < 36) return
    val context = LocalContext.current
    val container = LocalAppContainer.current
    var granted by remember { mutableStateOf(hasLocalNetworkPermission(context)) }

    val host = remember(container.server.baseUrl) {
        runCatching { java.net.URI(container.server.baseUrl).host.orEmpty() }.getOrDefault("")
    }
    LaunchedEffect(container.server.baseUrl) {
        granted = hasLocalNetworkPermission(context)
    }
    if (granted || !container.server.isLanHost(host)) return

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
    }
    val palette = LocalZhixuePalette.current

    Surface(
        modifier = modifier.fillMaxWidth().padding(10.dp),
        color = palette.accentSubtle,
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.primary.copy(alpha = 0.45f)),
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                "需要「本地网络」权限",
                color = palette.primary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
            Text(
                "当前后端是局域网地址 $host。Android 16 起没有该权限时请求会被系统拦下，表现为连接超时。",
                color = palette.text,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { launcher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) }) {
                    Text("授予权限", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private fun hasLocalNetworkPermission(context: android.content.Context): Boolean {
    if (Build.VERSION.SDK_INT < 36) return true
    return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) ==
        PackageManager.PERMISSION_GRANTED
}

/** 供其它入口（如测试连接按钮）复用。 */
fun localNetworkPermissionGranted(context: android.content.Context): Boolean = hasLocalNetworkPermission(context)

