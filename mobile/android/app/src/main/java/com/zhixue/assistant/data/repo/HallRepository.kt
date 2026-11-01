package com.zhixue.assistant.data.repo

import com.zhixue.assistant.core.ApiClient
import com.zhixue.assistant.core.jsonBody
import com.zhixue.assistant.data.model.DmMessageDto
import com.zhixue.assistant.data.model.FriendData
import com.zhixue.assistant.data.model.HallChannel
import com.zhixue.assistant.data.model.HallMessageDto
import com.zhixue.assistant.data.model.OnlineUser
import com.zhixue.assistant.data.model.SpaceNote
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.put

class HallRepository(private val api: ApiClient) {

    /** 第一项恒为公共大厅（courseId=null），其后是课程频道。 */
    suspend fun channels(): List<HallChannel> = api.get("/api/hall/channels")

    /** courseId 为空表示公共大厅；limit 后端裁剪到 1..200。 */
    suspend fun history(courseId: Long?, limit: Int = 50): List<HallMessageDto> =
        api.get("/api/hall/messages${api.query("courseId" to courseId, "limit" to limit)}")

    suspend fun spaceNotes(courseId: Long): List<SpaceNote> =
        api.get("/api/hall/space/notes?courseId=$courseId")

    /** 拉私聊记录有副作用：把对方发来的消息置为已读并清掉对应铃铛通知（与 Web 行为一致）。 */
    suspend fun dmMessages(peerUserId: Long, limit: Int = 50): List<DmMessageDto> =
        api.get("/api/dm/messages${api.query("peerUserId" to peerUserId, "limit" to limit)}")

    suspend fun friends(): FriendData = api.get("/api/friends")

    suspend fun requestFriend(username: String) =
        api.postVoid("/api/friends/request", jsonBody { put("username", username.trim()) })

    suspend fun acceptFriend(requestId: Long) = api.postVoid("/api/friends/$requestId/accept", "{}")

    suspend fun rejectFriend(requestId: Long) = api.postVoid("/api/friends/$requestId/reject", "{}")

    /** 删除好友（status 必须是已同意），聊天记录保留但无法继续私聊。 */
    suspend fun removeFriend(requestId: Long) = api.deleteVoid("/api/friends/$requestId")
}

/** WS 在线名单帧的载荷，单独定义避免和 REST 的 FriendRow 混淆。 */
@Serializable
data class OnlinePayload(
    val courseId: Long? = null,
    val online: List<OnlineUser> = emptyList(),
    val onlineCount: Int = 0,
)

@Serializable
data class DmIncomingDto(
    val id: Long = 0,
    val fromUserId: Long = 0,
    val fromNickname: String = "",
    val fromAvatar: String? = null,
    val toUserId: Long = 0,
    val content: String = "",
    val sentAt: String? = null,
)

@Serializable
internal data class SystemFrameDto(val event: String = "", val nickname: String = "")

@Serializable
internal data class ErrorFrameDto(val message: String = "")

@Serializable
internal data class AiDeltaDto(val courseId: Long? = null, val delta: String = "")

@Serializable
internal data class AiCourseDto(val courseId: Long? = null, val message: String = "")
