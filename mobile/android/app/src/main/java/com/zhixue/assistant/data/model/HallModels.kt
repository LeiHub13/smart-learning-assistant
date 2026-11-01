package com.zhixue.assistant.data.model

import com.zhixue.assistant.util.TolerantBooleanNonNull
import com.zhixue.assistant.util.TolerantIntNonNull
import kotlinx.serialization.Serializable

/** 频道只有两种：公共大厅（courseId=null）与课程频道。 */
@Serializable
data class HallChannel(val courseId: Long? = null, val name: String = "")

/** REST 历史消息字段是 createdAt，WebSocket 实时消息字段是 sentAt，两套都要能接。 */
@Serializable
data class HallMessageDto(
    val id: Long = 0,
    val userId: Long = 0,
    val nickname: String = "",
    val avatar: String? = null,
    val courseId: Long? = null,
    /** 0=用户，1=AI 助教；WebSocket 的 AI 帧里这个字段是字符串 "ai"，因此必须容错 */
    @Serializable(with = TolerantIntNonNull::class) val role: Int? = null,
    val sources: String? = null,
    val content: String = "",
    val createdAt: String? = null,
    val sentAt: String? = null,
) {
    val timeRaw: String? get() = sentAt ?: createdAt
    val fromAi: Boolean get() = role == 1 || userId == 0L
}

@Serializable
data class OnlineUser(val userId: Long = 0, val nickname: String = "")

@Serializable
data class SpaceNote(
    val id: Long = 0,
    val userId: Long? = null,
    val author: String? = null,
    val kpName: String? = null,
    val title: String = "",
    val content: String = "",
    val updatedAt: String? = null,
)

@Serializable
data class DmMessageDto(
    val id: Long = 0,
    val fromUserId: Long = 0,
    val content: String = "",
    val createdAt: String? = null,
)

@Serializable
data class FriendRow(
    val requestId: Long = 0,
    val userId: Long = 0,
    val username: String = "",
    val nickname: String = "",
    val avatar: String? = null,
    @Serializable(with = TolerantBooleanNonNull::class) val online: Boolean? = null,
    val unread: Int = 0,
)

@Serializable
data class FriendPending(val requestId: Long = 0, val userId: Long = 0, val username: String = "", val nickname: String = "")

@Serializable
data class FriendData(
    val friends: List<FriendRow> = emptyList(),
    val incoming: List<FriendPending> = emptyList(),
    val outgoing: List<FriendPending> = emptyList(),
)

/** 公告（管理员发布，普通用户以通知形式收到）。 */
@Serializable
data class AnnouncementDto(
    val id: Long = 0,
    val adminId: Long? = null,
    val adminName: String? = null,
    val title: String = "",
    val content: String = "",
    @Serializable(with = TolerantBooleanNonNull::class) val emailSent: Boolean? = null,
    val createdAt: String? = null,
)
