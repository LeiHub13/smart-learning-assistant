package com.zhixue.assistant.core

/** 页面级异步状态：加载 / 错误（带重试）/ 已就绪，空数据由数据层自己判断并渲染 Empty 视图。 */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Error(val message: String) : UiState<Nothing>
    data class Ready<T>(val data: T) : UiState<T>
}

fun Throwable.userMessage(): String = when (this) {
    is ApiException -> message
    is NetworkException -> message
    else -> message?.takeIf { it.isNotBlank() } ?: "操作失败，请稍后重试"
}

/** 401 不做「自动重登」：后端没有刷新接口，和 Web 一样清登录态回到登录页。 */
fun Throwable.isAuthExpired(): Boolean = this is ApiException && isUnauthorized
