package com.zhixue.assistant.data.model

import com.zhixue.assistant.util.TolerantBooleanNonNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/** /api/courses 与 /api/courses/hub 的卡片视图（后端拼装的 Map，布尔位可能是 0/1）。 */
@Serializable
data class CourseCard(
    val id: Long = 0,
    val name: String = "",
    val description: String? = null,
    val ownerId: Long? = null,
    val ownerName: String? = null,
    @Serializable(with = TolerantBooleanNonNull::class) val inHub: Boolean? = null,
    @Serializable(with = TolerantBooleanNonNull::class) val enrolled: Boolean? = null,
    /** creator | joined */
    val role: String? = null,
    val kbCount: Int? = null,
    val docCount: Int? = null,
    val chunkCount: Int? = null,
    val questionCount: Int? = null,
    /** 仅 Hub 列表返回 */
    val memberCount: Int? = null,
) {
    val isCreator: Boolean get() = role == "creator"
}

@Serializable
data class CourseCreated(
    val id: Long = 0,
    val tenantId: Long? = null,
    val name: String = "",
    val description: String? = null,
    val ownerId: Long? = null,
    val ownerName: String? = null,
    val inHub: Int? = null,
    val createdAt: String? = null,
)

@Serializable
data class KnowledgeBaseDto(
    val id: Long = 0,
    val courseId: Long? = null,
    val name: String = "",
    val createdAt: String? = null,
)

@Serializable
data class DocumentDto(
    val id: Long = 0,
    val kbId: Long? = null,
    val fileName: String = "",
    val fileUrl: String? = null,
    val fileType: String? = null,
    val fileSize: Long? = null,
    val chunkCount: Int? = null,
    val parseStatus: String? = null,
    /** AI 速览 JSON 字符串，可能为 null */
    val overview: String? = null,
    val createdAt: String? = null,
)

/** 全库文档列表（/api/kb/docs）用的 key 是 docId 而不是 id。 */
@Serializable
data class DocListItemDto(
    val docId: Long = 0,
    val fileName: String = "",
    val kbId: Long? = null,
    val kbName: String? = null,
    val courseId: Long? = null,
    val courseName: String? = null,
    val fileType: String? = null,
    val chunkCount: Int? = null,
    val hasOverview: Boolean? = null,
    val fileUrl: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class DocPreviewDto(
    val docId: Long = 0,
    val fileName: String = "",
    val fileType: String? = null,
    val chunkCount: Int? = null,
    val parseStatus: String? = null,
    val text: String? = null,
    val fileUrl: String? = null,
)

@Serializable
data class OverviewResult(
    val summary: String = "",
    val points: List<String> = emptyList(),
    val examPoints: List<String> = emptyList(),
)

@Serializable
data class UploadResult(
    val id: Long = 0,
    val fileName: String = "",
    val chunkCount: Int = 0,
    val kbName: String = "",
)

@Serializable
data class GraphNodeDto(val name: String = "", val type: String = "概念")

@Serializable
data class GraphEdgeDto(val source: String = "", val target: String = "", val relation: String = "")

@Serializable
data class GraphDto(
    val kbId: Long = 0,
    val kbName: String = "",
    val nodes: List<GraphNodeDto> = emptyList(),
    val edges: List<GraphEdgeDto> = emptyList(),
    val nodeCount: Int = 0,
    val edgeCount: Int = 0,
    val stale: Boolean? = null,
    val updatedAt: String? = null,
)

@Serializable
data class ReindexResult(val indexed: Int = 0, val vectorCount: Int = 0)
