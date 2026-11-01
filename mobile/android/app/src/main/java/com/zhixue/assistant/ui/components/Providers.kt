package com.zhixue.assistant.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavHostController
import com.zhixue.assistant.AppContainer

/**
 * 应用级依赖通过 CompositionLocal 下发，ViewModel 用 factory 显式接收 container，
 * 避免屏幕之间靠全局单例互相偷数据。
 */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("ZhixueApp 尚未提供 AppContainer")
}

/** 轻提示（对应 Web 的 showHint toast），由 AppScaffold 挂 SnackbarHost。 */
val LocalToast = staticCompositionLocalOf<(String) -> Unit> { { _ -> } }

/**
 * 后端返回的 `navigate` 是 Web 路由（如 /practice?courseId=1&auto=1），
 * 由导航层翻译成 Android 目的地。
 */
val LocalNavigator = staticCompositionLocalOf<(String) -> Unit> { { _ -> } }

/** 需要跳到 Android 专属目的地（带参数的子页）时用它；跨域跳转优先走 LocalNavigator。 */
val LocalNavController = staticCompositionLocalOf<NavHostController> {
    error("ZhixueApp 尚未提供 NavController")
}
