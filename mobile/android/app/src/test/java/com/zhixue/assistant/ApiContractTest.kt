package com.zhixue.assistant

import com.zhixue.assistant.core.ApiClient
import com.zhixue.assistant.core.MeDto
import com.zhixue.assistant.core.ZhixueJson
import com.zhixue.assistant.core.unwrapData
import com.zhixue.assistant.core.unwrapOrNull
import com.zhixue.assistant.data.model.BillingOrder
import com.zhixue.assistant.data.model.BillingOverview
import com.zhixue.assistant.data.model.ChatSessionDto
import com.zhixue.assistant.data.model.CourseCard
import com.zhixue.assistant.data.model.DocListItemDto
import com.zhixue.assistant.data.model.DocumentDto
import com.zhixue.assistant.data.model.ExamDto
import com.zhixue.assistant.data.model.ExamWithQuestions
import com.zhixue.assistant.data.model.FavoriteItem
import com.zhixue.assistant.data.model.FlashcardDto
import com.zhixue.assistant.data.model.GraphDto
import com.zhixue.assistant.data.model.HallChannel
import com.zhixue.assistant.data.model.MailLogDto
import com.zhixue.assistant.data.model.MistakePage
import com.zhixue.assistant.data.model.PlanDetail
import com.zhixue.assistant.data.model.ProgressSummary
import com.zhixue.assistant.data.model.RecommendItem
import com.zhixue.assistant.data.model.ReportDto
import com.zhixue.assistant.data.model.SearchResults
import com.zhixue.assistant.data.model.StudySummary
import com.zhixue.assistant.data.model.TrendPoint
import com.zhixue.assistant.data.model.UnreadCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.zhixue.assistant.data.model.HallMessageDto

/**
 * 契约测试：样本是从本地跑起来的真实后端（8080/8123 分支同代码）抓的响应，
 * 用来守住「Android 模型字段 == 后端字段」这条线，字段改名会在这里先炸。
 */
class ApiContractTest {

    private inline fun <reified T> decode(json: String): T =
        unwrapData<T>(ApiClient.decodeEnvelope(json)?.data)

    @Test
    fun `auth me 的布尔位是字符串`() {
        val me = decode<MeDto>(
            """{"code":0,"message":"ok","data":{"id":"1","email":"a@b.c","mailMute":"dm,friend","nickname":"泡椒13","username":"pg13","avatar":"/files/avatars/u1-1.png","isAdmin":"false"}}"""
        )
        assertEquals(1L, me.userId)
        assertTrue(!me.admin)
        assertEquals(setOf("dm", "friend"), me.mutedMailTypes())
    }

    @Test
    fun `课程卡片的布尔位既可能是布尔也可能是 0 或 1`() {
        val boolStyle = decode<List<CourseCard>>(
            """{"code":0,"data":[{"id":2,"name":"Python 快速入门","description":"简介","ownerName":"泡椒13","ownerId":1,"inHub":false,"enrolled":true,"role":"creator","kbCount":1,"docCount":1,"chunkCount":5,"questionCount":12}]}"""
        )
        assertEquals("creator", boolStyle.single().role)
        assertTrue(boolStyle.single().inHub == false)
        assertTrue(boolStyle.single().enrolled == true)

        val intStyle = decode<List<CourseCard>>(
            """{"code":0,"data":[{"id":3,"name":"数据结构与算法","inHub":1,"enrolled":0,"memberCount":4}]}"""
        )
        assertEquals(true, intStyle.single().inHub)
        assertEquals(false, intStyle.single().enrolled)
        assertEquals(4, intStyle.single().memberCount)
    }

    @Test
    fun `答疑会话的 kbScope 与引导模式可为空`() {
        val sessions = decode<List<ChatSessionDto>>(
            """{"code":0,"data":[{"id":68,"userId":1,"courseId":null,"kbId":null,"kbScope":null,"socratic":false,"feynman":false,"title":"你好","createdAt":"2026-10-13T21:15:23"},{"id":66,"userId":1,"courseId":1,"kbId":13,"kbScope":"course","socratic":true,"feynman":false,"title":"什么是操作系统","createdAt":"2026-10-13T20:00:00"}]}"""
        )
        assertTrue(!sessions.first().kbMode)
        assertEquals(2, sessions.size)
        val kbSession = sessions[1]
        assertTrue(kbSession.kbMode)
        assertTrue(!kbSession.scopeSingle)
        assertEquals(true, kbSession.socratic)
    }

    @Test
    fun `文档列表与全库文档的 key 不同`() {
        val docs = decode<List<DocumentDto>>(
            """{"code":0,"data":[{"id":31,"kbId":13,"fileName":"java笔记.md","fileUrl":"/files/kb-docs/kb13-31-java笔记.md","fileType":"text/markdown","fileSize":1291,"chunkCount":5,"parseStatus":"SUCCESS","overview":null,"createdAt":"2026-09-27T22:04:25"}]}"""
        )
        assertEquals(31L, docs.single().id)
        assertNull(docs.single().overview)

        val flat = decode<List<DocListItemDto>>(
            """{"code":0,"data":[{"docId":31,"fileName":"java笔记.md","kbId":13,"kbName":"新知识库","courseId":1,"courseName":"Java 编程基础","fileType":"text/markdown","chunkCount":5,"hasOverview":false,"fileUrl":"/files/kb-docs/kb13-31-java笔记.md","createdAt":"2026-09-27T22:04:25"}]}"""
        )
        assertEquals(31L, flat.single().docId)
        assertEquals("Java 编程基础", flat.single().courseName)
    }

    @Test
    fun `知识图谱未生成时 data 是 null 而不是错误`() {
        val envelope = ApiClient.decodeEnvelope("""{"code":0,"message":"ok","data":null}""")
        assertEquals(0, envelope?.code)
        assertNull(unwrapOrNull<GraphDto>(envelope?.data))
    }

    @Test
    fun `学习时长日历与推荐位字段`() {
        val summary = decode<StudySummary>(
            """{"code":0,"data":{"totalMinutes":1497,"activeDays":19,"calendar":[{"date":"2026-08-10","minutes":0},{"date":"2026-08-11","minutes":35}],"byCourse":[{"courseId":1,"courseName":"Java 编程基础","minutes":300}]}}"""
        )
        assertEquals(1497, summary.totalMinutes)
        assertEquals(2, summary.calendar.size)
        assertEquals(300, summary.byCourse.single().minutes)

        val recommends = decode<List<RecommendItem>>(
            """{"code":0,"data":[{"type":"practice_kp","title":"专项练习：Spring Boot 自动配置","reason":"正确率 0% (0/3)","kpName":"Spring Boot 自动配置","action":"/practice?courseId=1&kp=Spring Boot 自动配置"}]}"""
        )
        assertTrue(recommends.single().action!!.startsWith("/practice"))
    }

    @Test
    fun `掌握度百分比是浮点`() {
        val progress = decode<ProgressSummary>(
            """{"code":0,"data":{"masteries":[{"id":10,"userId":1,"courseId":1,"kpName":"Spring Boot 核心注解","mastery":100.0,"attempts":1,"correctCount":1,"lastPracticeAt":"2026-09-26T14:06:10"}],"averageMastery":72,"totalAttempts":9,"wrongBook":[{"stem":"题干","kpName":"集合","userAnswer":"A","answer":"B","wrongAt":"2026-09-26T14:06:10"}],"advice":null,"adviceCached":false}}"""
        )
        assertEquals(100, progress.masteries.single().percent)
        assertEquals(1, progress.wrongBook.size)
        assertTrue(progress.advice == null)
    }

    @Test
    fun `趋势百分比字段`() {
        val trend = decode<List<TrendPoint>>(
            """{"code":0,"data":[{"date":"2026-08-21","title":"多线程综合练习","score":45,"totalScore":50,"rate":90.0},{"date":"2026-08-22","title":"集合专项练习","score":30,"totalScore":30,"rate":100.0}]}"""
        )
        assertEquals(90.0, trend.first().rate, 0.001)
        assertEquals("2026-08-21", trend.first().date)
    }

    @Test
    fun `计划详情任务与日期`() {
        val detail = decode<PlanDetail>(
            """{"code":0,"data":{"plan":{"id":3,"userId":1,"courseId":1,"goal":"java容器","days":7,"status":"done","createdAt":"2026-09-25T11:26:43"},"tasks":[{"id":15,"planId":3,"dayNo":1,"taskDate":"2026-09-25","title":"集合框架","tasks":"看源码\n写demo","focusKp":"List","done":true,"doneAt":"2026-09-25T18:00:00"}],"doneCount":1}}"""
        )
        assertEquals("done", detail.plan.status)
        assertEquals(listOf("看源码", "写demo"), detail.tasks.single().taskLines)
        assertEquals(true, detail.tasks.single().done)
    }

    @Test
    fun `考试详情包含每题分值`() {
        val exam = decode<ExamWithQuestions>(
            """{"code":0,"data":{"exam":{"id":4,"tenantId":1,"userId":1,"courseId":1,"title":"java","durationMin":5,"totalScore":50,"createdAt":"2026-09-26T14:09:44"},"questions":[{"id":39,"type":"问答","stem":"简述 Spring Boot 自动配置原理","options":null,"kpName":"自动配置","score":25}]}}"""
        )
        assertEquals(50, exam.exam.totalScore)
        assertEquals(25, exam.questions.single().score)
    }

    @Test
    fun `考试列表附带我的成绩`() {
        val list = decode<List<ExamDto>>(
            """{"code":0,"data":[{"id":4,"title":"java","courseId":1,"courseName":"Java 编程基础","durationMin":5,"totalScore":50,"createdAt":"2026-09-26T14:09:44","owner":true,"attemptCount":1,"myBestScore":13}]}"""
        )
        assertEquals(true, list.single().owner)
        assertEquals(13, list.single().myBestScore)
    }

    @Test
    fun `收藏与错题记录字段`() {
        val favorites = decode<List<FavoriteItem>>(
            """{"code":0,"data":[{"questionId":3,"courseId":1,"courseName":"Java 编程基础","type":"判断","stem":"synchronized 修饰的实例方法锁住的是当前对象。","kpName":"Java多线程","difficulty":"进阶","options":"null","answer":"对","analysis":"解析","lastAnswer":"对","lastCorrect":true}]}"""
        )
        assertEquals(3L, favorites.single().questionId)
        assertEquals(true, favorites.single().lastCorrect)

        val mistakes = decode<MistakePage>(
            """{"code":0,"data":{"records":[{"question":{"id":56,"courseId":2,"type":"问答","stem":"元组解包","answer":"参考答案","analysis":null,"kpName":"元组","difficulty":"基础","source":"SEED","createdAt":"2026-09-20T10:00:00"},"lastWrongAt":"2026-09-30T10:00:00","lastWrongAnswer":"我的答案","wrongCount":2}],"total":7}}"""
        )
        assertEquals(7, mistakes.total)
        assertEquals(2, mistakes.records.single().wrongCount)
    }

    @Test
    fun `闪卡队列字段`() {
        val cards = decode<List<FlashcardDto>>(
            """{"code":0,"data":[{"id":7,"tenantId":1,"userId":1,"courseId":1,"source":"mistake","sourceId":45,"front":"问题正面","back":"答案背面","box":2,"lastResult":"miss","lastReviewedAt":"2026-10-01T10:00:00","dueAt":"2026-10-01T10:10:00","createdAt":"2026-09-30T09:00:00"}]}"""
        )
        assertEquals(2, cards.single().box)
        assertEquals("mistake", cards.single().source)
    }

    @Test
    fun `频道列表公共大厅第一项 courseId 为空`() {
        val channels = decode<List<HallChannel>>(
            """{"code":0,"data":[{"courseId":null,"name":"公共大厅"},{"courseId":6,"name":"Linux 操作系统实践"}]}"""
        )
        assertNull(channels.first().courseId)
        assertEquals(2, channels.size)
    }

    @Test
    fun `对话厅 AI 帧的 role 是字符串 ai 也必须能解出`() {
        // REST 历史里 role 是 0/1，WebSocket 的 AI 回复里 role 是 "ai"，同一个模型要吃得下两种
        val ai = ZhixueJson.decodeFromString(
            HallMessageDto.serializer(),
            """{"id":9,"userId":0,"nickname":"AI 助教","avatar":"","courseId":3,"role":"ai","sources":"1,2","content":"回答正文","sentAt":"2026-11-01T18:00:00"}"""
        )
        assertTrue(ai.fromAi)
        assertEquals("1,2", ai.sources)
        assertEquals("2026-11-01T18:00:00", ai.timeRaw)

        val human = ZhixueJson.decodeFromString(
            HallMessageDto.serializer(),
            """{"id":10,"userId":2,"nickname":"小明","courseId":null,"role":0,"content":"大家好","createdAt":"2026-11-01T18:01:00"}"""
        )
        assertTrue(!human.fromAi)
        assertEquals("2026-11-01T18:01:00", human.timeRaw)
    }

    @Test
    fun `通知未读与邮件日志`() {
        assertEquals(0, decode<UnreadCount>("""{"code":0,"data":{"count":0}}""").count)
        val mails = decode<List<MailLogDto>>(
            """{"code":0,"data":[{"id":17,"tenantId":1,"userId":1,"email":"a@b.c","subject":"【智学助手】复习提醒","content":"正文","status":"SENT","error":null,"createdAt":"2026-10-11T08:00:00"}]}"""
        )
        assertEquals("SENT", mails.single().status)
    }

    @Test
    fun `次数包总览价格单位是分且下单说明原样`() {
        val overview = decode<BillingOverview>(
            """{"code":0,"data":{"enabled":true,"balance":10,"initialCredits":10,"orderingAvailable":true,"paymentInstructions":"本地联调：转账到测试账户，备注订单号","paymentUrl":"","packages":[{"code":"starter","name":"轻量次数包","credits":100,"priceFen":990},{"code":"standard","name":"常用次数包","credits":250,"priceFen":1990}]}}"""
        )
        assertEquals(2, overview.packages.size)
        assertEquals(990, overview.packages.first().priceFen)
        assertEquals("", overview.paymentUrl)
    }

    @Test
    fun `订单为空数组而不是 null`() {
        val orders = decode<List<BillingOrder>>("""{"code":0,"data":[]}""")
        assertTrue(orders.isEmpty())
    }

    @Test
    fun `报告正文是 Markdown 字符串`() {
        val reports = decode<List<ReportDto>>(
            """{"code":0,"data":[{"id":2,"userId":1,"courseId":1,"period":"weekly","title":"学习周报 2026-09-12","content":"# 学习周报（近 7 天）\n\n> 统计周期","createdAt":"2026-09-12T10:00:00"}]}"""
        )
        assertTrue(reports.single().content.startsWith("# "))
    }

    @Test
    fun `搜索结果的分组与截断`() {
        val results = decode<SearchResults>(
            """{"code":0,"data":{"keyword":"Java","questions":[{"id":2,"courseId":1,"type":"多选","stem":"下列属于 List 接口实现类的有？","kpName":"Java集合框架"}],"notes":[],"exams":[],"docs":[{"chunkId":9,"kbName":"新知识库","docName":"java笔记.md","content":"片段摘要"}]}}"""
        )
        assertEquals(1, results.questions.size)
        assertEquals("Java", results.keyword)
        assertEquals(9L, results.docs.single().chunkId)
    }

    @Test
    fun `业务错误的中文消息可以直接展示`() {
        val envelope = ApiClient.decodeEnvelope("""{"code":400,"message":"无权访问管理看板","data":null}""")
        assertEquals(400, envelope?.code)
        assertEquals("无权访问管理看板", envelope?.message)
    }
}
