package com.life0423.carddock.platform

import android.app.*
import android.content.*
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.life0423.carddock.CardDockApplication
import com.life0423.carddock.MainActivity
import com.life0423.carddock.core.rule.LocalReminder
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val FIRE = "com.life0423.carddock.REMINDER"
internal const val ALARM_CHANNEL = "personal-reminders"
@Serializable private data class AlarmEntry(val plan: LocalReminder, val due: Long?, val firedAt: Long? = null)

/** 调度以已保存的卡片为准，删除、改时和暂停都替换同一个卡片专属 PendingIntent。 */
internal class LocalAlarms(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val prefs = context.getSharedPreferences("local-reminders",Context.MODE_PRIVATE)
    private val entries = Json.decodeFromString<Map<String,AlarmEntry>>(prefs.getString("items","{}")!!).toMutableMap()
    val changes = kotlinx.coroutines.flow.MutableStateFlow(0)
    init { manager.createNotificationChannel(NotificationChannel(ALARM_CHANNEL,"个人定时提醒",NotificationManager.IMPORTANCE_HIGH)) }
    fun precise() = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
    fun notifications() = manager.areNotificationsEnabled() && manager.getNotificationChannel(ALARM_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    fun lastFired(id: String) = entries[id]?.firedAt
    fun scheduled(id: String) = entries[id]?.due
    private fun intent(id: String, due: Long) = PendingIntent.getBroadcast(context,0,
        Intent(context,LocalReminderReceiver::class.java).setAction(FIRE).setData(Uri.fromParts("carddock-reminder",id,null)).putExtra("due",due),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    /** 写入实际登记状态后再通知正在查看空间或详情的用户界面。 */
    private fun save() { prefs.edit().putString("items",Json.encodeToString(entries.toMap())).apply(); changes.value++ }

    fun sync(plans: Map<String,LocalReminder>, reset: Boolean = false) {
        var changed = false
        for (id in entries.keys.toSet() - plans.keys) {
            alarms.cancel(intent(id,0));manager.cancel(id,1);entries.remove(id);changed=true
        }
        for ((id,plan) in plans) {
            val old = entries[id]
            val active = plan.enabled && notifications() && precise()
            // 冷启动可能正为过期闹钟启动进程，保留待投递时间，交给接收器核对。
            if (!reset && old?.plan == plan && (old.due != null && active || old.due == null && (!active || plan.date != null && plan.next(ZonedDateTime.now()) == null))) continue
            alarms.cancel(intent(id,0))
            if (old?.plan != plan || !active) manager.cancel(id,1)
            val due = if (active) plan.next(ZonedDateTime.now())?.toInstant()?.toEpochMilli() else null
            if (due != null) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,due,intent(id,due))
            entries[id] = AlarmEntry(plan,due,old?.takeIf { it.plan == plan }?.firedAt)
            changed=true
        }
        if (changed) save()
    }

    /** 已删除、已改时或重复送达的旧广播不会触发新计划。 */
    fun deliver(id: String, due: Long, plans: Map<String,LocalReminder>) {
        val entry = entries[id] ?: return
        if (entry.due != due || plans[id] != entry.plan || !entry.plan.enabled) return
        if (!notifications() || !precise()) { entries[id] = entry.copy(due=null); save(); return }
        val link = PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        manager.notify(id,1,NotificationCompat.Builder(context,ALARM_CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(entry.plan.subject).setContentText("卡坞定时提醒 · ${entry.plan.clock}")
            .setContentIntent(link).setAutoCancel(true).build())
        entries[id] = entry.copy(due=null,firedAt=System.currentTimeMillis());save()
        if (entry.plan.date == null) sync(plans)
    }
}

class LocalReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = (context.applicationContext as CardDockApplication).container
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                val data = app.controller.state.first { !it.loading }
                if (data.cardsReadable) {
                    val plans = data.cards.mapNotNull { c -> c.rule.localReminder?.let { c.id to it } }.toMap()
                    if (intent.action == FIRE) intent.data?.schemeSpecificPart?.let { app.reminders.deliver(it,intent.getLongExtra("due",-1),plans) }
                    else app.reminders.sync(plans,reset=true)
                }
            } finally { pending.finish() }
        }
    }
}
