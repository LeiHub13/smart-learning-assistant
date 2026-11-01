package com.zhixue.assistant.ui.nav

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.zhixue.assistant.AppContainer
import com.zhixue.assistant.ui.components.LocalAppContainer
import com.zhixue.assistant.ui.components.LocalNavigator
import com.zhixue.assistant.ui.components.UserAvatar
import com.zhixue.assistant.ui.theme.LocalZhixuePalette
import kotlinx.coroutines.launch
import androidx.compose.material3.TextButton
import com.zhixue.assistant.core.ThemeMode

data class TabItem(val label: String, val route: String, val icon: ImageVector, val prefix: String)

/** 底部主导航：手机上最高频的五件事；其余功能全部在抽屉里成组列出，不做「藏起来」。 */
val primaryTabs = listOf(
    TabItem("首页", Routes.HOME, Icons.Default.Home, "home"),
    TabItem("答疑", Routes.chat(), Icons.Default.Send, "chat"),
    TabItem("对话厅", Routes.hall(), Icons.Default.List, "hall"),
    TabItem("练习", Routes.practice(), Icons.Default.Star, "practice"),
    TabItem("我的", Routes.PROFILE, Icons.Default.Person, "profile"),
)

data class DrawerSection(val title: String, val items: List<DrawerItem>)
data class DrawerItem(val label: String, val route: String, val adminOnly: Boolean = false)

/** 分组与 Web 侧边栏一致，方便从 Web 习惯迁移过来。 */
val drawerSections = listOf(
    DrawerSection(
        "AI 与答疑",
        listOf(
            DrawerItem("智能答疑", Routes.chat()),
            DrawerItem("AI 生成讲义/练习题", Routes.GENERATE),
            DrawerItem("对话厅", Routes.hall()),
        )
    ),
    DrawerSection(
        "课程学习",
        listOf(
            DrawerItem("我的课程", Routes.MANAGE),
            DrawerItem("课程 Hub", Routes.HUB),
            DrawerItem("文档管理", Routes.docs()),
            DrawerItem("学习笔记", Routes.notes()),
        )
    ),
    DrawerSection(
        "练习与测验",
        listOf(
            DrawerItem("题库练习", Routes.practice()),
            DrawerItem("错题本", Routes.MISTAKES),
            DrawerItem("闪卡", Routes.FLASHCARDS),
            DrawerItem("收藏夹", Routes.FAVORITES),
            DrawerItem("题库管理", Routes.BANK),
            DrawerItem("在线考试", Routes.EXAM),
        )
    ),
    DrawerSection(
        "学情与规划",
        listOf(
            DrawerItem("学情分析", Routes.PROGRESS),
            DrawerItem("学习报告", Routes.REPORTS),
            DrawerItem("学习计划", Routes.PLANS),
        )
    ),
    DrawerSection(
        "账号与其他",
        listOf(
            DrawerItem("首页看板", Routes.HOME),
            DrawerItem("全局搜索", Routes.search("")),
            DrawerItem("通知中心", Routes.NOTIFICATIONS),
            DrawerItem("AI 次数与套餐", Routes.BILLING),
            DrawerItem("个人中心", Routes.PROFILE),
            DrawerItem("服务器设置", Routes.SERVER),
            DrawerItem("系统看板", Routes.ADMIN, adminOnly = true),
        )
    ),
)

private val titlePatterns: List<Pair<String, String>> = listOf(
    "home" to "智学助手",
    "chat" to "智能答疑",
    "hall" to "对话厅",
    "generate" to "AI 内容生成",
    "practice" to "题库练习",
    "practice-report" to "练习报告",
    "mistakes" to "错题本",
    "favorites" to "收藏夹",
    "exam" to "在线考试",
    "exam-take" to "在线考试",
    "exam-report" to "成绩单",
    "bank" to "题库管理",
    "question-editor" to "题目录入",
    "flashcards" to "闪卡",
    "progress" to "学情分析",
    "manage" to "我的课程",
    "course" to "课程与知识库",
    "hub" to "课程 Hub",
    "docs" to "文档管理",
    "doc" to "文档预览",
    "graph" to "知识图谱",
    "plans" to "学习计划",
    "reports" to "学习报告",
    "report-detail" to "报告详情",
    "notes" to "学习笔记",
    "note-editor" to "笔记编辑",
    "profile" to "个人中心",
    "billing" to "AI 次数与套餐",
    "admin" to "系统看板",
    "search" to "搜索",
    "notifications" to "通知中心",
    "server" to "服务器设置",
)

fun titleFor(route: String?): String {
    val head = route?.trim('/')?.substringBefore('/')?.substringBefore('?') ?: return "智学助手"
    return titlePatterns.firstOrNull { (prefix, _) -> head == prefix }?.second
        ?: titlePatterns.firstOrNull { (prefix, _) -> head.startsWith("$prefix-") }?.second
        ?: "智学助手"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    container: AppContainer,
    navController: NavHostController,
    snackbarHostState: SnackbarHostState,
    showBack: Boolean,
    onBack: () -> Unit,
    bottomBarRoute: String,
    onTabSelect: (String) -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    val palette = LocalZhixuePalette.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val session by container.session.session.collectAsState()
    val unread by container.notifications.unread.collectAsState()
    val me = session?.me

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            DrawerContent(
                nickname = me?.nickname?.ifBlank { me.username }.orEmpty(),
                email = me?.email.orEmpty(),
                avatar = me?.avatar,
                isAdmin = me?.admin == true,
                navigate = { route ->
                    scope.launch { drawerState.close() }
                    navController.navigate(route) { launchSingleTop = true }
                },
                signOut = {
                    scope.launch { drawerState.close() }
                    container.onSignedOut()
                },
            )
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            titleFor(bottomBarRoute),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            fontSize = 18.sp,
                        )
                    },
                    navigationIcon = {
                        if (showBack) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                            }
                        } else {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "全部功能")
                            }
                        }
                    },
                    actions = {
                        // Web 顶栏是太阳/月亮图标；这里用文字按钮，三种模式一眼可辨，长按回「跟随系统」
                        val mode = container.settings.theme.collectAsState().value
                        TextButton(onClick = {
                            val next = when (mode) {
                                ThemeMode.System -> ThemeMode.Light
                                ThemeMode.Light -> ThemeMode.Dark
                                ThemeMode.Dark -> ThemeMode.System
                            }
                            container.settings.setTheme(next)
                        }) {
                            Text(
                                when (mode) {
                                    ThemeMode.System -> "主题·自动"
                                    ThemeMode.Light -> "主题·浅色"
                                    ThemeMode.Dark -> "主题·深色"
                                },
                                fontSize = 12.sp,
                                color = palette.text,
                            )
                        }
                        IconButton(onClick = { navController.navigate(Routes.search("")) }) {
                            Icon(Icons.Default.Search, contentDescription = "全局搜索")
                        }
                        IconButton(onClick = { navController.navigate(Routes.NOTIFICATIONS) }) {
                            BadgedBox(
                                badge = {
                                    if (unread > 0) Badge { Text(if (unread > 99) "99+" else "$unread") }
                                }
                            ) {
                                Icon(Icons.Default.Notifications, contentDescription = "通知")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = palette.card,
                        titleContentColor = palette.text,
                        navigationIconContentColor = palette.text,
                        actionIconContentColor = palette.text,
                    ),
                )
            },
            bottomBar = { BottomBar(selectedRoute = bottomBarRoute, onSelect = onTabSelect) },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = palette.canvas,
            content = content,
        )
    }
}

@Composable
fun BottomBar(
    selectedRoute: String,
    onSelect: (String) -> Unit,
) {
    val palette = LocalZhixuePalette.current
    val head = selectedRoute.trim('/').substringBefore('/').substringBefore('?')
    NavigationBar(containerColor = palette.card, tonalElevation = 0.dp) {
        primaryTabs.forEach { tab ->
            NavigationBarItem(
                selected = head == tab.prefix,
                onClick = { onSelect(tab.route) },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label, fontSize = 11.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = palette.primary,
                    selectedTextColor = palette.primary,
                    unselectedIconColor = palette.muted,
                    unselectedTextColor = palette.muted,
                    indicatorColor = palette.accentSubtle,
                ),
            )
        }
    }
}

@Composable
private fun DrawerContent(
    nickname: String,
    email: String,
    avatar: String?,
    isAdmin: Boolean,
    navigate: (String) -> Unit,
    signOut: () -> Unit,
) {
    val palette = LocalZhixuePalette.current
    ModalDrawerSheet(modifier = Modifier.width(310.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                UserAvatar(name = nickname, avatarPath = avatar, size = 44.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(nickname.ifBlank { "未登录" }, fontWeight = FontWeight.SemiBold)
                    Text(
                        email.ifBlank { "未绑定邮箱" },
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.muted,
                    )
                }
            }
            drawerSections.forEach { section ->
                Text(
                    section.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
                )
                section.items.filter { !it.adminOnly || isAdmin }.forEach { item ->
                    NavigationDrawerItem(
                        label = { Text(item.label) },
                        selected = false,
                        onClick = { navigate(item.route) },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                        shape = RoundedCornerShape(8.dp),
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Surface(
                color = palette.soft,
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = signOut)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("退出登录", color = palette.danger)
                    Icon(
                        Icons.AutoMirrored.Filled.ExitToApp,
                        contentDescription = null,
                        tint = palette.danger,
                        modifier = Modifier.width(18.dp),
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

/** 次数徽标：billing 未开启时不占位；余额与说明全部来自后端。 */
@Composable
fun QuotaBadge(compact: Boolean = false, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val navigate = LocalNavigator.current
    val palette = LocalZhixuePalette.current
    val overview by container.quota.collectAsState()
    val data = overview ?: return
    if (!data.enabled) return
    Surface(
        color = palette.accentSubtle,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.clickable { navigate(Routes.BILLING) },
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (compact) "次数 ${data.balance}" else "AI 次数 ${data.balance} · 查看套餐",
                color = palette.primary,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
