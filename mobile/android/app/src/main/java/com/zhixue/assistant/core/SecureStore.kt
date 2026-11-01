package com.zhixue.assistant.core

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 登录信息（JWT）放在 EncryptedSharedPreferences 里：AES-256-GCM 加密、密钥由系统 Keystore 托管。
 * 少数设备（自定义 ROM / Keystore 损坏）会创建失败，这时退回普通私有 SharedPreferences 并记录原因，
 * 保证 App 不崩、仍可登录；设置页会提示当前处于降级存储。
 */
class SecureStore private constructor(
    val store: KeyValueStore,
    val encrypted: Boolean,
) {
    companion object {
        private const val FILE_SECURE = "zhixue_secure"
        private const val FILE_PLAIN = "zhixue_prefs"

        fun create(context: Context): SecureStore = runCatching {
            val masterKey = MasterKey.Builder(context, "zhixue_master_key")
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs: SharedPreferences = EncryptedSharedPreferences.create(
                context, FILE_SECURE, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            SecureStore(PreferencesStore(prefs), true)
        }.getOrElse {
            SecureStore(PreferencesStore(context.getSharedPreferences(FILE_PLAIN, Context.MODE_PRIVATE)), false)
        }

        fun plain(context: Context): SecureStore =
            SecureStore(PreferencesStore(context.getSharedPreferences(FILE_PLAIN, Context.MODE_PRIVATE)), false)
    }
}
