package com.life0423.carddock

import android.content.Intent
import com.life0423.carddock.ui.taskNoticeIntentForDock
import com.wangzan101.mika.task.TaskNotice
import com.wangzan101.mika.MikaApp

/** 进程入口。容器放在 onCreate，这时 Application context 已经可用。 */
class CardDockApplication : MikaApp(), TaskNotice.Host {
    override fun taskNoticeIntent(notice: TaskNotice): Intent = taskNoticeIntentForDock(notice)

    override val manualShopping = true

    lateinit var container: AppContainer
        private set

    private lateinit var routePrices: com.life0423.carddock.platform.RoutePriceSubscriptions

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        routePrices = com.life0423.carddock.platform.RoutePriceSubscriptions(this)
    }
}
