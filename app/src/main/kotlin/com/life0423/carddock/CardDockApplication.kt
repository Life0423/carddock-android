package com.life0423.carddock

import android.app.Application

/** 进程入口。容器放在 onCreate，这时 Application context 已经可用。 */
class CardDockApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
