package com.life0423.carddock.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.app.NotificationCompat
import com.life0423.carddock.ui.MikaTasksActivity
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.task.DockRoutes
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

private const val ROUTE_PRICES = 42318
private const val CHANNEL = "route-price-drops"

/** 订阅属于空间卡。草稿、模板和原有官网核价任务分别管理。 */
internal class RoutePriceSubscriptions(private val app: Context) : SharedPreferences.OnSharedPreferenceChangeListener {
    private val store = DockRoutes(app)
    private val scheduler = app.getSystemService(JobScheduler::class.java)
    private val notices = app.getSystemService(NotificationManager::class.java)
    private val previous = store.all().filter { it.isCard && it.resultAt > 0 && it.price != null }.associate { it.id to it.price!! }.toMutableMap()
    init {
        store.prefs.registerOnSharedPreferenceChangeListener(this)
        schedule()
    }

    private fun schedule() {
        val any = store.all().any { it.isCard && it.daily && it.cardIntent?.price == true }
        if (any && scheduler.getPendingJob(ROUTE_PRICES) == null) {
            scheduler.schedule(JobInfo.Builder(ROUTE_PRICES, ComponentName(app, RoutePriceJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
                .setPeriodic(TimeUnit.DAYS.toMillis(1)).build())
        } else if (!any && scheduler.getPendingJob(ROUTE_PRICES) != null) scheduler.cancel(ROUTE_PRICES)
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
        schedule()
        if (key == null) return
        val card = store.get(key)
        if (card == null) { previous.remove(key); notices.cancel(key, ROUTE_PRICES); return }
        val price = card.price ?: return
        if (card.resultAt == 0L) previous.remove(key)
        if (!card.isCard || card.resultAt == 0L || card.phase != com.wangzan101.mika.task.DockRoute.Phase.Ready) return
        val old = previous.put(key, price) ?: return
        if (!card.notifyDrop || old.key != price.key || price.high != null || old.high != null ||
            price.from || old.from || price.low >= old.low || !notices.areNotificationsEnabled()) return
        notices.createNotificationChannel(NotificationChannel(CHANNEL, "商品降价提醒", NotificationManager.IMPORTANCE_DEFAULT))
        val open = Intent(app, MikaTasksActivity::class.java).putExtra("routes", true).putExtra("routeId", key)
            .setData(android.net.Uri.fromParts("carddock-price", key, null))
        notices.notify(key, ROUTE_PRICES, NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_more).setContentTitle(price.key.product + " 降价")
            .setContentText(old.display + " → " + price.display).setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(app, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
    }
}

/** 每次调用前重读订阅，串行占用助手。系统停止调度时不继续启动下一张卡。 */
class RoutePriceJob : JobService() {
    private var work: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        work = CoroutineScope(Dispatchers.IO).launch {
            val routes = DockRoutes(application)
            var retry = false
            for (id in routes.all().filter { it.isCard && it.daily }.map { it.id }) {
                val card = routes.get(id) ?: continue
                if (card.busy || !card.daily) continue
                routes.execute(id, scheduled = true)
                val task = routes.get(id)?.taskId ?: continue
                while (routes.get(id)?.let { it.taskId == task && it.busy } == true) {
                    delay(1000)
                    val reason = when (val state = routes.reconcile(task)?.state) {
                        is TaskState.Held -> when (state.reason.code) {
                            "waiting_unlock" -> "手机尚未解锁"
                            "waiting_user", "paused" -> state.reason.why.ifBlank { "手机助手需要用户处理" }
                            else -> null
                        }
                        is TaskState.WaitingInput, is TaskState.WaitingConfirmation, is TaskState.WaitingTakeover -> "目标应用需要用户处理"
                        else -> null
                    }
                    if (reason != null) routes.stop(id, expectedTaskId = task, scheduledIssue = "每日核价待补查：$reason")
                }
                if (routes.get(id)?.phase == com.wangzan101.mika.task.DockRoute.Phase.Failed) retry = true
            }
            jobFinished(params, retry)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true
    }
}
