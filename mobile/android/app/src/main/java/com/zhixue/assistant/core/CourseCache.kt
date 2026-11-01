package com.zhixue.assistant.core

import com.zhixue.assistant.data.model.CourseCard
import com.zhixue.assistant.data.repo.CourseRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 课程数据全局缓存（Web 端也是模块级缓存，登录后加载一次）。
 * 退出登录、切换服务器、增删课程后必须失效，否则会看到上一台后端或已删除的课程。
 */
class CourseCache(private val repo: CourseRepository) {

    private val _courses = MutableStateFlow<List<CourseCard>>(emptyList())
    val courses: StateFlow<List<CourseCard>> = _courses.asStateFlow()

    private var loaded = false
    private var generation = 0

    suspend fun ensure(force: Boolean = false): List<CourseCard> {
        if (loaded && !force) return _courses.value
        val list = repo.myCourses()
        _courses.value = list
        loaded = true
        return list
    }

    fun clear() {
        loaded = false
        generation++
        _courses.value = emptyList()
    }

    fun nameOf(courseId: Long?): String? = courseId?.let { id -> _courses.value.firstOrNull { it.id == id }?.name }

    fun firstCourseId(): Long? = _courses.value.firstOrNull()?.id
}
