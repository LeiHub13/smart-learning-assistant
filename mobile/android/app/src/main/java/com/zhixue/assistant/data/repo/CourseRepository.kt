package com.zhixue.assistant.data.repo

import com.zhixue.assistant.core.ApiClient
import com.zhixue.assistant.core.jsonBody
import com.zhixue.assistant.core.putOpt
import com.zhixue.assistant.data.model.CourseCard
import com.zhixue.assistant.data.model.CourseCreated
import com.zhixue.assistant.data.model.DocListItemDto
import com.zhixue.assistant.data.model.DocPreviewDto
import com.zhixue.assistant.data.model.DocumentDto
import com.zhixue.assistant.data.model.GraphDto
import com.zhixue.assistant.data.model.KnowledgeBaseDto
import com.zhixue.assistant.data.model.OverviewResult
import com.zhixue.assistant.data.model.ReindexResult
import com.zhixue.assistant.data.model.UploadResult
import kotlinx.serialization.json.put

class CourseRepository(private val api: ApiClient) {

    /** 我创建 + 我加入的课程；前端全局缓存，登录切换服务器时要清空。 */
    suspend fun myCourses(): List<CourseCard> = api.get("/api/courses")

    suspend fun hub(): List<CourseCard> = api.get("/api/courses/hub")

    suspend fun create(name: String, description: String, inHub: Boolean): CourseCreated =
        api.post(
            "/api/courses",
            jsonBody {
                put("name", name.trim())
                putOpt("description", description.trim())
                put("inHub", inHub)
            }
        )

    /** 仅课程创建者可设置，后端有校验。 */
    suspend fun setHub(courseId: Long, inHub: Boolean) =
        api.postVoid("/api/courses/$courseId/hub", jsonBody { put("inHub", inHub) })

    suspend fun enroll(courseId: Long) = api.postVoid("/api/courses/$courseId/enroll", "{}")

    suspend fun unenroll(courseId: Long) = api.deleteVoid("/api/courses/$courseId/enroll")

    /** 删除课程会级联清理知识库、题库、练习、笔记、计划等（后端 @Transactional）。 */
    suspend fun deleteCourse(courseId: Long) = api.deleteVoid("/api/courses/$courseId")

    suspend fun knowledgeBases(courseId: Long): List<KnowledgeBaseDto> = api.get("/api/courses/$courseId/kb")

    suspend fun createKb(courseId: Long, name: String): KnowledgeBaseDto =
        api.post("/api/courses/$courseId/kb", jsonBody { put("name", name.trim().ifBlank { "新知识库" }) })

    suspend fun deleteKb(kbId: Long) = api.deleteVoid("/api/kb/$kbId")

    suspend fun documents(kbId: Long): List<DocumentDto> = api.get("/api/kb/$kbId/documents")

    /** 全库文档列表（文档管理页）。 */
    suspend fun allDocuments(): List<DocListItemDto> = api.get("/api/kb/docs")

    /** 上传原始文件由后端转发 ai-service 解析；支持 txt/md/java/json/xml/yml/yaml/sql/pdf/docx，旧版 .doc 不支持。 */
    suspend fun uploadDocument(kbId: Long, fileName: String, bytes: ByteArray, mime: String): UploadResult =
        api.upload("/api/kb/$kbId/documents/upload", fileName = fileName, bytes = bytes, mime = mime)

    /** 粘贴文本入库：同样走 multipart，把文本当作 text/plain 文件。 */
    suspend fun uploadText(kbId: Long, fileName: String, content: String): UploadResult =
        api.upload(
            "/api/kb/$kbId/documents/upload",
            fileName = fileName,
            bytes = content.toByteArray(Charsets.UTF_8),
            mime = "text/plain",
        )

    suspend fun renameDocument(kbId: Long, docId: Long, fileName: String): DocumentDto =
        api.put("/api/kb/$kbId/documents/$docId/rename", jsonBody { put("fileName", fileName.trim()) })

    suspend fun deleteDocument(kbId: Long, docId: Long) = api.deleteVoid("/api/kb/$kbId/documents/$docId")

    suspend fun preview(kbId: Long, docId: Long): DocPreviewDto = api.get("/api/kb/$kbId/documents/$docId/preview")

    /** AI 速览：新生成或重新生成，返回结构化要点。 */
    suspend fun overview(kbId: Long, docId: Long): OverviewResult =
        api.post("/api/kb/$kbId/documents/$docId/overview", "{}")

    /** 图谱可能从未生成过，此时后端返回 null，需要走「生成图谱」。 */
    suspend fun graph(kbId: Long): GraphDto? = api.getOrNull("/api/kb/$kbId/graph")

    suspend fun rebuildGraph(kbId: Long): GraphDto = api.post("/api/kb/$kbId/graph/rebuild", "{}")

    /** 向量索引是可丢弃的派生数据，检索异常时重建。 */
    suspend fun reindex(): ReindexResult = api.post("/api/kb/reindex", "{}")
}
