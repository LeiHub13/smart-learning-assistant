package com.zhixue.assistant.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SseAndEnvelopeTest {

    private fun frames(vararg lines: String): List<SseFrame> {
        val parser = SseFrameParser()
        val out = mutableListOf<SseFrame>()
        lines.forEach { line -> parser.line(line)?.let { out += it } }
        return out
    }

    @Test
    fun `delta 与结束事件按空行成帧`() {
        val result = frames(
            "data:{\"delta\":\"你\"}",
            "",
            "data:{\"delta\":\"好\"}",
            "",
            "data:{\"sources\":\"1,2\",\"sourceChunks\":\"11,12\",\"followups\":\"[\\\"为什么\\\"]\",\"actions\":[]}",
            "",
        )
        assertEquals(3, result.size)
        assertEquals("message", result[0].event)
        assertTrue(result[0].data.contains("你"))
        val final = result[2].data.let { ZhixueJson.parseToJsonElement(it).jsonObject }
        assertEquals("1,2", final.textOf("sources"))
        assertEquals("11,12", final.textOf("sourceChunks"))
    }

    @Test
    fun `通知流只带 event 行时也要成帧`() {
        val result = frames("event:refresh", "data:{\"t\":1730000000000}", "")
        assertEquals(1, result.size)
        assertEquals("refresh", result[0].event)
    }

    @Test
    fun `多行 data 用换行拼接且不解析错误的行不崩溃`() {
        val parser = SseFrameParser()
        assertNull(parser.line("data:{\"delta\":\"a"))
        assertNull(parser.line("not-a-field"))
        val frame = parser.line("")
        assertEquals("message", frame?.event)
        assertEquals("{\"delta\":\"a", frame?.data)
    }

    @Test
    fun `注释行与心跳注释被忽略`() {
        assertEquals(0, frames(":keep-alive", "").size)
    }

    @Test
    fun `结尾没有空行时补发最后一帧`() {
        val parser = SseFrameParser()
        parser.line("data:{\"saved\":42}")
        val flushed = parser.line("")
        assertEquals("message", flushed?.event)
        assertTrue(flushed!!.data.contains("42"))
    }

    @Test
    fun `信封解析覆盖成功_业务错误_未登录三种响应`() {
        val ok = ApiClient.decodeEnvelope("""{"code":0,"message":"ok","data":{"id":7}}""")
        assertEquals(0, ok?.code)
        assertEquals(7L, ok?.data?.jsonObject?.get("id").toString().toLong())

        val biz = ApiClient.decodeEnvelope("""{"code":400,"message":"AI 次数不足，请前往个人中心购买次数包","data":null}""")
        assertEquals(400, biz?.code)
        assertEquals("AI 次数不足，请前往个人中心购买次数包", biz?.message)

        val expired = ApiClient.decodeEnvelope("""{"code":401,"message":"未登录或登录已过期"}""")
        assertEquals(401, expired?.code)
        assertTrue(ApiException(401, "x").isUnauthorized)
        assertTrue(!ApiException(403, "无权访问管理看板").isUnauthorized)
    }

    @Test
    fun `非 JSON 响应不抛解析异常而是返回 null 由上层翻译`() {
        assertNull(ApiClient.decodeEnvelope("<html>502 Bad Gateway</html>"))
    }

    @Test
    fun `Unit 类型的 data 为空不报错_其它类型为空则报错`() {
        assertEquals(Unit, unwrapData<Unit>(null))
        assertNull(unwrapOrNull<JsonObject>(null))
        val error = runCatching { unwrapData<Tokens>(null) }.exceptionOrNull()
        assertTrue(error is ApiException)
    }

    @Test
    fun `网络异常描述对用户友好`() {
        assertTrue(
            ApiClient.describe(java.net.ConnectException("failed to connect"))
                .contains("无法连接后端")
        )
        assertTrue(
            ApiClient.describe(java.io.IOException("CLEARTEXT communication to 10.0.2.2 not permitted"))
                .contains("明文")
        )
    }
}
