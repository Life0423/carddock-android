package com.life0423.carddock

import android.app.Application
import android.app.NotificationManager
import com.life0423.carddock.core.rule.LocalReminder
import com.life0423.carddock.platform.LocalAlarms
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 验证改时、重复投递及通知授权恢复后，调度以最新卡片设置为准。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=Application::class)
class LocalAlarmsTest {
    @Test fun deniedDeliveryCanBeArmedAgainAndOldTimeCannotNotify() {
        val ctx=RuntimeEnvironment.getApplication()
        val notices=shadowOf(ctx.getSystemService(NotificationManager::class.java))
        val alarms=LocalAlarms(ctx)
        val plan=LocalReminder("测试",10,20)
        val plans=mapOf("test" to plan)
        alarms.sync(plans)
        val first=requireNotNull(alarms.scheduled("test"))
        notices.setNotificationsEnabled(false)
        alarms.deliver("test",first,plans)
        assertNull(alarms.scheduled("test"))
        notices.setNotificationsEnabled(true)
        alarms.sync(plans)
        assertNotNull(alarms.scheduled("test"))
        val edited=mapOf("test" to plan.copy(minute=21))
        alarms.sync(edited)
        assertNotEquals(first,alarms.scheduled("test"))
        alarms.deliver("test",first,edited)
        assertNull(alarms.lastFired("test"))
        alarms.sync(mapOf("test" to plan.copy(enabled=false)))
        assertNull(alarms.scheduled("test"))
        alarms.sync(emptyMap())
        assertNull(alarms.lastFired("test"))
    }
}
