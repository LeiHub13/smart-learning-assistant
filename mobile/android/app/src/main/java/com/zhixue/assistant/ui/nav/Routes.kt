package com.zhixue.assistant.ui.nav

/**
 * 目的地集中定义。参数一律走路径段或查询串，和 Web 路由一一对应，
 * 这样后端返回的 `navigate`（例如 /practice?courseId=1&auto=1）可以直接翻译进来。
 */
object Routes {
    const val WELCOME = "welcome"
    const val LOGIN = "login"

    const val HOME = "home"
    const val HALL = "hall"
    const val GENERATE = "generate"
    const val MISTAKES = "mistakes"
    const val FAVORITES = "favorites"
    const val EXAM = "exam"
    const val BANK = "bank"
    const val FLASHCARDS = "flashcards"
    const val PROGRESS = "progress"
    const val MANAGE = "manage"
    const val HUB = "hub"
    const val DOCS = "docs"
    const val PLANS = "plans"
    const val REPORTS = "reports"
    const val NOTES = "notes"
    const val PROFILE = "profile"
    const val BILLING = "billing"
    const val ADMIN = "admin"
    const val SEARCH = "search"
    const val NOTIFICATIONS = "notifications"
    const val SERVER = "server"

    const val ARG_SESSION_ID = "sessionId"
    const val ARG_COURSE_ID = "courseId"
    const val ARG_KB_ID = "kbId"
    const val ARG_DOC_ID = "docId"
    const val ARG_PRACTICE_ID = "practiceId"
    const val ARG_RECORD_ID = "recordId"
    const val ARG_EXAM_ID = "examId"
    const val ARG_REPORT_ID = "reportId"
    const val ARG_NOTE_ID = "noteId"
    const val ARG_QUESTION_ID = "questionId"
    const val ARG_PEER_ID = "peerId"
    const val ARG_MODE = "mode"
    const val ARG_KP = "kp"
    const val ARG_ASK = "ask"
    const val ARG_REL = "rel"
    const val ARG_AUTO = "auto"
    const val ARG_IDS = "ids"
    const val ARG_QUERY = "q"
    const val ARG_SCOPE = "scope"

    /** 会话 id 用 -1 表示「还没有会话」，这样 chat 的 pattern 始终只有一条。 */
    const val NO_SESSION = -1L
    const val NO_COURSE = -1L

    const val CHAT = "chat/{$ARG_SESSION_ID}?courseId={$ARG_COURSE_ID}&kbId={$ARG_KB_ID}" +
        "&scope={$ARG_SCOPE}&ask={$ARG_ASK}&rel={$ARG_REL}"
    const val PRACTICE = "practice/{$ARG_MODE}?courseId={$ARG_COURSE_ID}&kp={$ARG_KP}&auto={$ARG_AUTO}&ids={$ARG_IDS}"
    const val COURSE_DETAIL = "course/{$ARG_COURSE_ID}"
    const val DOC_PREVIEW = "doc/{$ARG_KB_ID}/{$ARG_DOC_ID}"
    const val GRAPH = "graph/{$ARG_KB_ID}?courseId={$ARG_COURSE_ID}"
    const val PRACTICE_REPORT = "practice-report/{$ARG_PRACTICE_ID}"
    const val EXAM_TAKE = "exam-take/{$ARG_EXAM_ID}"
    const val EXAM_REPORT = "exam-report/{$ARG_RECORD_ID}"
    const val REPORT_DETAIL = "report-detail/{$ARG_REPORT_ID}"
    const val NOTE_EDITOR = "note-editor/{$ARG_NOTE_ID}"
    const val QUESTION_EDITOR = "question-editor/{$ARG_QUESTION_ID}"

    fun chat(
        sessionId: Long? = null,
        courseId: Long? = null,
        kbId: Long? = null,
        scope: String = "single",
        ask: String = "",
        rel: String = "",
    ): String = "chat/${sessionId ?: NO_SESSION}" +
        "?courseId=${courseId ?: NO_COURSE}&kbId=${kbId ?: NO_COURSE}&scope=$scope" +
        "&ask=" + encode(ask) + "&rel=" + encode(rel)

    /** mode: normal | mistake | favorite | manual */
    fun practice(
        mode: String = "normal",
        courseId: Long? = null,
        kp: String = "",
        auto: Boolean = false,
        ids: String = "",
    ): String = "practice/$mode?courseId=${courseId ?: NO_COURSE}" +
        "&kp=${encode(kp)}&auto=${auto.toString()}&ids=${encode(ids)}"

    fun courseDetail(courseId: Long) = "course/$courseId"

    fun docPreview(kbId: Long, docId: Long) = "doc/$kbId/$docId"

    /** courseId 可为空：图谱页拿不到时会按 kbId 反查所属课程，用于节点「去答疑」。 */
    fun graph(kbId: Long, courseId: Long? = null): String =
        if (courseId == null) "graph/$kbId" else "graph/$kbId?courseId=$courseId"

    fun practiceReport(practiceId: Long) = "practice-report/$practiceId"

    fun examTake(examId: Long) = "exam-take/$examId"

    fun examReport(recordId: Long) = "exam-report/$recordId"

    fun reportDetail(reportId: Long) = "report-detail/$reportId"

    fun noteEditor(noteId: Long) = "note-editor/$noteId"

    fun questionEditor(questionId: Long) = "question-editor/$questionId"

    fun notes(courseId: Long? = null) = "notes?courseId=${courseId ?: NO_COURSE}"

    fun docs(courseId: Long? = null) = "docs?courseId=${courseId ?: NO_COURSE}"

    fun search(query: String) = "search?q=${encode(query)}"

    fun hall(peerId: Long? = null) = "hall?peerId=${peerId ?: NO_COURSE}"

    fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
}
