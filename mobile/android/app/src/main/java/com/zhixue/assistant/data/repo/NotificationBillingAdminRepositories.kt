package com.zhixue.assistant.data.repo

import com.zhixue.assistant.core.ApiClient
import com.zhixue.assistant.core.SseClient
import com.zhixue.assistant.core.SseMessage
import com.zhixue.assistant.core.jsonBody
import com.zhixue.assistant.data.model.AdminDashboard
import com.zhixue.assistant.data.model.AdminUserPage
import com.zhixue.assistant.data.model.AnnouncementDto
import com.zhixue.assistant.data.model.BillingOrder
import com.zhixue.assistant.data.model.BillingOverview
import com.zhixue.assistant.data.model.MailLogDto
import com.zhixue.assistant.data.model.NotificationDto
import com.zhixue.assistant.data.model.RecallResult
import com.zhixue.assistant.data.model.UnreadCount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.put

@Serializable
data class NotificationCreated(val id: Long = 0)

class NotificationRepository(
    private val api: ApiClient,
    private val sse: SseClient,
) {

    suspend fun list(): List<NotificationDto> = api.get("/api/notifications")

    suspend fun unreadCount(): UnreadCount = api.get("/api/notifications/unread-count")

    suspend fun markRead(id: Long) = api.postVoid("/api/notifications/$id/read", "{}")

    suspend fun markAllRead() = api.postVoid("/api/notifications/read-all", "{}")

    suspend fun mailLogs(): List<MailLogDto> = api.get("/api/notifications/mails")

    /** 长连接 SSE：只关心 event:refresh，收到即重拉列表与未读数；连接关闭后由调用方重连。 */
    fun refreshSignals(): Flow<Unit> = sse.keepAlive("/api/notifications/stream")
        .filterIsInstance<SseMessage.Refresh>()
        .map { Unit }
}

/** 付款平台仅这四种，与后端 submit 的校验一致。 */
val PAYMENT_CHANNELS = listOf("AFDIAN" to "爱发电", "WECHAT" to "微信", "ALIPAY" to "支付宝", "OTHER" to "其他")

class BillingRepository(private val api: ApiClient) {

    /** 余额、套餐、付款说明、是否可下单全部来自后端；客户端不写死任何价格。 */
    suspend fun overview(): BillingOverview = api.get("/api/billing/overview")

    suspend fun orders(): List<BillingOrder> = api.get("/api/billing/orders")

    suspend fun createOrder(packageCode: String): BillingOrder =
        api.post("/api/billing/orders", jsonBody { put("packageCode", packageCode) })

    /** 提交付款信息只是进入「待核对」，不会自动到账。 */
    suspend fun submit(orderNo: String, channel: String, reference: String): BillingOrder =
        api.post(
            "/api/billing/orders/$orderNo/submit",
            jsonBody {
                put("channel", channel)
                put("reference", reference.trim())
            }
        )

    /** 只有 WAITING 状态可取消。 */
    suspend fun cancel(orderNo: String) = api.postVoid("/api/billing/orders/$orderNo/cancel", "{}")
}

class AdminRepository(private val api: ApiClient) {

    suspend fun dashboard(): AdminDashboard = api.get("/api/admin/dashboard")

    suspend fun users(keyword: String?, page: Int = 1, size: Int = 10): AdminUserPage =
        api.get("/api/admin/users" + api.query("keyword" to keyword?.takeIf { it.isNotBlank() }, "page" to page, "size" to size))

    suspend fun announcements(): List<AnnouncementDto> = api.get("/api/announcements")

    suspend fun publish(title: String, content: String, notifyEmail: Boolean) =
        api.postVoid(
            "/api/announcements",
            jsonBody {
                put("title", title.trim())
                put("content", content)
                put("notifyEmail", notifyEmail)
            }
        )

    suspend fun recall(id: Long): RecallResult = api.delete("/api/announcements/$id")
}
