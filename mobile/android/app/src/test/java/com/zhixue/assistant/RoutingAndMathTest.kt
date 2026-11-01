package com.zhixue.assistant

import com.zhixue.assistant.core.ApiException
import com.zhixue.assistant.core.MeDto
import com.zhixue.assistant.core.MemoryStore
import com.zhixue.assistant.core.ServerConfig
import com.zhixue.assistant.core.SessionStore
import com.zhixue.assistant.core.Tokens
import com.zhixue.assistant.data.model.GraphEdgeDto
import com.zhixue.assistant.data.model.GraphNodeDto
import com.zhixue.assistant.ui.nav.Routes
import com.zhixue.assistant.ui.nav.WebRoute
import com.zhixue.assistant.ui.components.layoutGraph
import com.zhixue.assistant.ui.components.neighborsFor
import com.zhixue.assistant.util.ChartMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import com.zhixue.assistant.core.userMessage

class RoutingAndMathTest {

    // ---------- 服务器地址 ----------

    private fun server(allowHttp: Boolean = true, store: MemoryStore = MemoryStore()) =
        ServerConfig(store, "http://10.0.2.2:8080", allowHttp)

    @Test
    fun `地址归一化去掉尾部斜杠并补协议`() {
        val config = server()
        assertEquals("http://10.0.2.2:8080", config.normalize("http://10.0.2.2:8080/").getOrNull())
        assertEquals("https://example.com", config.normalize("example.com").getOrNull())
        assertEquals("https://a.test:8443", config.normalize("https://a.test:8443///").getOrNull())
    }

    @Test
    fun `非法地址给出可读原因而不是崩溃`() {
        assertTrue(server().normalize("   ").isFailure)
        assertTrue(server().normalize("ht:p/xx").isFailure)
        assertTrue(server().normalize("localhost:8080 api").isFailure)
    }

    @Test
    fun `正式包拒绝非本机明文地址`() {
        val strict = server(allowHttp = false)
        assertTrue(strict.normalize("http://192.168.1.5:8080").isSuccess)
        assertTrue(strict.normalize("http://203.0.113.9:8080").isFailure)
        assertTrue(strict.normalize("https://203.0.113.9").isSuccess)
    }

    @Test
    fun `ws 与 wss 同 http 源一致`() {
        val config = server()
        config.applyBaseUrl("https://study.example.com")
        assertEquals("wss://study.example.com/ws/hall", config.websocketUrl("/ws/hall"))
        config.applyBaseUrl("http://192.168.1.5:8080")
        assertEquals("ws://192.168.1.5:8080/ws/hall", config.websocketUrl("/ws/hall"))
    }

    @Test
    fun `相对资源按当前服务器解析`() {
        val config = server()
        config.applyBaseUrl("http://192.168.1.5:8080")
        assertEquals("http://192.168.1.5:8080/files/avatars/u1-a.png", config.resolve("/files/avatars/u1-a.png"))
        assertEquals("https://cdn.x/y", config.resolve("https://cdn.x/y"))
    }

    // ---------- 登录态按服务器分片 ----------

    @Test
    fun `切换服务器不会把旧 token 带过去`() {
        val store = MemoryStore()
        val config = server(store = store)
        val session = SessionStore(store, config)

        config.applyBaseUrl("http://10.0.2.2:8080")
        session.signIn(Tokens("token-A"), MeDto(id = "1", username = "a", nickname = "A"))
        assertEquals("token-A", session.accessToken)

        config.applyBaseUrl("https://other.example")
        assertNull(session.accessToken)
        assertFalse(session.isLoggedIn)

        session.signIn(Tokens("token-B"), MeDto(id = "2", username = "b"))
        assertEquals("token-B", session.accessToken)

        config.applyBaseUrl("http://10.0.2.2:8080")
        assertEquals("token-A", session.accessToken)
        assertEquals("A", session.me?.nickname)
    }

    @Test
    fun `退出只清当前服务器的会话记录`() {
        val store = MemoryStore()
        val config = server(store = store)
        val session = SessionStore(store, config)
        config.applyBaseUrl("http://10.0.2.2:8080")
        session.signIn(Tokens("t1"), MeDto(id = "1"))
        session.saveLastChatSession(88L)
        config.applyBaseUrl("https://other.example")
        session.signIn(Tokens("t2"), MeDto(id = "2"))
        session.saveLastChatSession(99L)
        config.applyBaseUrl("http://10.0.2.2:8080")
        assertEquals(88L, session.session.value?.lastChatSessionId)
        session.signOut()
        assertNull(session.accessToken)
        config.applyBaseUrl("https://other.example")
        assertEquals(99L, session.session.value?.lastChatSessionId)
    }

    @Test
    fun `me 接口的 isAdmin 是字符串 true`() {
        val me = com.zhixue.assistant.core.ZhixueJson.decodeFromString(MeDto.serializer(), """
            {"id":"7","isAdmin":"true","username":"admin","nickname":"管理员","email":"","avatar":"","mailMute":"dm,friend"}
        """.trim())
        assertTrue(me.admin)
        assertEquals(7L, me.userId)
        assertEquals(setOf("dm", "friend"), me.mutedMailTypes())
    }

    // ---------- Web 路由翻译 ----------

    @Test
    fun `后端 navigate 字段翻译到 Android 目的地`() {
        assertEquals(Routes.HOME, WebRoute.toAndroid("/home"))
        assertEquals(Routes.BILLING, WebRoute.toAndroid("/billing"))
        assertTrue(WebRoute.toAndroid("/practice?courseId=3&auto=1")!!.startsWith("practice/normal?courseId=3"))
        assertTrue(WebRoute.toAndroid("/practice?favorite=1&courseId=2")!!.contains("practice/favorite"))
        assertTrue(WebRoute.toAndroid("/hall?peer=5")!!.contains("peerId=5"))
        assertTrue(WebRoute.toAndroid("/chat?courseId=1&kbId=2&ask=牛顿定律&rel=力,质量")!!.contains("ask="))
        assertNull(WebRoute.toAndroid(null))
    }

    @Test
    fun `聊天目的地与会话参数拼装一致`() {
        val route = Routes.chat(sessionId = 12L, courseId = 3L, kbId = 5L, scope = "course", ask = "什么是 定积分", rel = "极限,面积")
        assertTrue(route.startsWith("chat/12?"))
        assertTrue(route.contains("courseId=3"))
        assertTrue(route.contains("kbId=5"))
        assertTrue(route.contains("scope=course"))
        val translated = WebRoute.toAndroid("/chat?sessionId=12&courseId=3&kbId=5&kbScope=course&ask=什么是%20定积分&rel=极限,面积")
        assertNotNull(translated)
    }

    @Test
    fun `未知路径不猜测目的地`() {
        assertNull(WebRoute.toAndroid("/"))
        assertTrue(WebRoute.toAndroid("/totally-unknown")!!.contains("totally-unknown"))
    }

    // ---------- 图表数学 ----------

    @Test
    fun `热力图分档与 Web 一致`() {
        assertEquals(0, ChartMath.heatmapLevel(0))
        assertEquals(1, ChartMath.heatmapLevel(29))
        assertEquals(2, ChartMath.heatmapLevel(30))
        assertEquals(3, ChartMath.heatmapLevel(90))
        assertEquals(4, ChartMath.heatmapLevel(120))
    }

    @Test
    fun `连续天数从今天或昨天起算`() {
        val today = LocalDate.of(2026, 11, 1)
        val data = mapOf(
            "2026-10-30" to 10,
            "2026-10-31" to 25,
            "2026-11-01" to 5,
            "2026-10-28" to 30,
        )
        assertEquals(3, ChartMath.streakDays(data, today))
        // 今天没学但昨天学了，连胜不断
        assertEquals(2, ChartMath.streakDays(data - "2026-11-01", today))
        assertEquals(0, ChartMath.streakDays(emptyMap(), today))
    }

    @Test
    fun `坐标映射在单点与空序列时不崩`() {
        assertEquals(listOf(50f), ChartMath.xPositions(1, 0f, 100f))
        assertEquals(2, ChartMath.xPositions(2, 0f, 100f).size)
        assertEquals(100f, ChartMath.xPositions(2, 0f, 100f).last())
        assertTrue(ChartMath.xPositions(0, 0f, 100f).isEmpty())
        // 画布 y 向下为正：最小值落到底部、最大值落到顶部，越界值被夹住
        assertEquals(110f, ChartMath.yFor(0.0, 0.0, 100.0, top = 10f, bottom = 110f), 0.001f)
        assertEquals(10f, ChartMath.yFor(100.0, 0.0, 100.0, top = 10f, bottom = 110f), 0.001f)
        assertEquals(60f, ChartMath.yFor(50.0, 0.0, 100.0, top = 10f, bottom = 110f), 0.001f)
        // 越界值夹住：超过上限落到顶部，负值落到底部
        assertEquals(10f, ChartMath.yFor(500.0, 0.0, 100.0, top = 10f, bottom = 110f), 0.001f)
        assertEquals(110f, ChartMath.yFor(-3.0, 0.0, 100.0, top = 10f, bottom = 110f), 0.001f)
        assertEquals(2, ChartMath.nearestIndex(0.6f, 5))
    }

    // ---------- 图谱 ----------

    @Test
    fun `图谱布局给每个节点一个位置且度数越大半径越大`() {
        val nodes = listOf(GraphNodeDto("牛顿定律", "概念"), GraphNodeDto("微积分", "方法"), GraphNodeDto("极限", "术语"))
        val edges = listOf(
            GraphEdgeDto("牛顿定律", "微积分", "依赖"),
            GraphEdgeDto("牛顿定律", "极限", "包含"),
        )
        val layout = layoutGraph(nodes, edges, 800f, 600f)
        assertEquals(3, layout.size)
        assertTrue(layout.all { it.x in 0f..800f && it.y in 0f..600f })
        val hub = layout.maxBy { it.degree }
        assertEquals("牛顿定律", hub.name)
        assertEquals(2, hub.degree)
        val leaf = layout.first { it.name == "微积分" }
        assertTrue(hub.radius > leaf.radius)
        assertTrue(layoutGraph(emptyList(), emptyList(), 800f, 600f).isEmpty())
    }

    @Test
    fun `邻居取最多四个且不含自己`() {
        val edges = (1..8).map { GraphEdgeDto("中心", "点$it", "关系") }
        assertEquals(4, neighborsFor(edges, "中心").size)
        assertFalse(neighborsFor(edges, "中心").contains("中心"))
        assertTrue(neighborsFor(edges, "").isEmpty())
    }

    @Test
    fun `业务错误与鉴权错误分类正确`() {
        assertFalse(ApiException(403, "无权访问管理看板").isUnauthorized)
        assertTrue(ApiException(401, "未登录或登录已过期").isUnauthorized)
        assertEquals("AI 次数不足，请前往个人中心购买次数包", ApiException(400, "AI 次数不足，请前往个人中心购买次数包").userMessage())
    }
}
