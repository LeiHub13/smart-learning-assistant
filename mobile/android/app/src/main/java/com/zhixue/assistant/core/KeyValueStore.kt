package com.zhixue.assistant.core

import android.content.SharedPreferences

/** 可注入的键值存储：生产用 SharedPreferences，单测用内存实现。 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun getInt(key: String, default: Int): Int
    fun put(key: String, value: String?)
    fun remove(key: String)
    fun keysWithPrefix(prefix: String): Set<String>
}

class PreferencesStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)

    override fun put(key: String, value: String?) = prefs.edit().apply {
        if (value == null) remove(key) else putString(key, value)
    }.apply()

    override fun remove(key: String) = prefs.edit().remove(key).apply()

    override fun keysWithPrefix(prefix: String): Set<String> =
        prefs.all.keys.filter { it.startsWith(prefix) }.toSet()
}

class MemoryStore(private val map: MutableMap<String, String> = mutableMapOf()) : KeyValueStore {
    override fun getString(key: String): String? = map[key]
    override fun getInt(key: String, default: Int): Int = map[key]?.toIntOrNull() ?: default

    override fun put(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }

    override fun remove(key: String) {
        map.remove(key)
    }

    override fun keysWithPrefix(prefix: String): Set<String> =
        map.keys.filter { it.startsWith(prefix) }.toSet()
}
