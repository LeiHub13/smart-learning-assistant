package com.zhixue.assistant.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.zhixue.assistant.AppContainer
import com.zhixue.assistant.ui.components.LocalAppContainer
import com.zhixue.assistant.ui.components.LocalNavController
import com.zhixue.assistant.ui.components.LocalNavigator
import com.zhixue.assistant.ui.components.LocalToast
import com.zhixue.assistant.ui.nav.AppScaffold
import com.zhixue.assistant.ui.nav.GuestNavGraph
import com.zhixue.assistant.ui.nav.MainNavGraph
import com.zhixue.assistant.ui.nav.Routes
import com.zhixue.assistant.ui.nav.WebRoute
import com.zhixue.assistant.ui.theme.ZhixueTheme
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.statusBarsPadding
import com.zhixue.assistant.ui.components.LocalNetworkGate

/**
 * 应用根：装配三件全局能力（容器、轻提示、Web 路由翻译），并按登录态在
 * 「欢迎/登录」与「主功能图」之间切换。token 被 401 清空时不需要任何手动跳转，
 * session flow 变化会自动把界面切回登录页。
 */
@Composable
fun ZhixueAppRoot(container: AppContainer) {
    val session by container.session.session.collectAsState()
    val themeMode by container.settings.theme.collectAsState()
    val signedIn = session?.tokens?.accessToken?.isNotBlank() == true

    ZhixueTheme(themeMode = themeMode) {
        if (signedIn) {
            MainRoot(container)
        } else {
            GuestRoot(container)
        }
    }
}

@Composable
private fun GuestRoot(container: AppContainer) {
    val navController = rememberNavController()
    GlobalProviders(container, navController) {
        GuestNavGraph(navController, container)
    }
}

private val primaryHeads = setOf("home", "chat", "hall", "practice", "profile")

@Composable
private fun MainRoot(container: AppContainer) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    GlobalProviders(container, navController, snackbarHostState) {
        val entry by navController.currentBackStackEntryAsState()
        val route = entry?.destination?.route ?: Routes.HOME
        val head = route.trim('/').substringBefore('/').substringBefore('?')
        val showBack = head !in primaryHeads

        AppScaffold(
            container = container,
            navController = navController,
            snackbarHostState = snackbarHostState,
            showBack = showBack,
            onBack = { navController.popBackStack() },
            bottomBarRoute = route,
            onTabSelect = { tabRoute ->
                navController.navigate(tabRoute) {
                    launchSingleTop = true
                    restoreState = true
                }
            },
        ) { innerPadding ->
            Box(Modifier.padding(innerPadding)) {
                MainNavGraph(navController, container)
            }
        }

        // 服务器地址变化时，把用户送回可读的起始页，避免停留在上一台后端的数据上
        LaunchedEffect(container.server.baseUrl) {
            navController.navigate(Routes.HOME) {
                popUpTo(Routes.HOME) { inclusive = false }
                launchSingleTop = true
            }
        }
    }
}

/** 三个 CompositionLocal 的统一装配点。 */
@Composable
private fun GlobalProviders(
    container: AppContainer,
    navController: NavHostController,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val toast: (String) -> Unit = remember(scope) {
        { message ->
            if (message.isNotBlank()) {
                scope.launch { snackbarHostState.showSnackbar(message) }
            }
        }
    }
    val navigator: (String) -> Unit = remember(navController) {
        { webRoute ->
            val destination = WebRoute.toAndroid(webRoute)
            if (destination == null) {
                Toast.makeText(context, "暂不支持跳转：$webRoute", Toast.LENGTH_SHORT).show()
            } else {
                navController.navigate(destination) { launchSingleTop = true }
            }
        }
    }

    CompositionLocalProvider(
        LocalAppContainer provides container,
        LocalToast provides toast,
        LocalNavigator provides navigator,
        LocalNavController provides navController,
    ) {
        Box(Modifier.fillMaxSize()) {
            content()
            // 局域网后端 + Android 16：没有「本地网络」权限时请求会被系统丢弃，这里给出可恢复的入口
            LocalNetworkGate(
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
            )
        }
    }
}
