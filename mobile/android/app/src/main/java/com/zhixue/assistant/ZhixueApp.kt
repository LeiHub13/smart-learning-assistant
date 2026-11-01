package com.zhixue.assistant

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

class ZhixueApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.bootIfSignedIn()

        // 学习时长只在应用处于前台时累计，等价于 Web 的 document.visibilityState 判断
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                container.heartbeat.setForeground(true)
                if (container.session.isLoggedIn) container.notifications.start()
            }

            override fun onStop(owner: LifecycleOwner) {
                container.heartbeat.setForeground(false)
            }
        })
    }
}
