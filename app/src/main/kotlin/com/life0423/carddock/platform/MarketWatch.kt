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
import androidx.core.app.NotificationCompat
import com.life0423.carddock.CardDockApplication
import com.life0423.carddock.MainActivity
import com.life0423.carddock.core.model.CardRecord
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

internal const val WATCH_CHANNEL = "official-price-falls"
internal const val WATCH_JOB = 42317

@Serializable internal data class PricePoint(val yuan: Int, val at: Long)
@Serializable internal data class PriceHistory(val changes: List<PricePoint>, val checkedAt: Long,
    val backgroundChecks: List<Long> = emptyList()) {
    /** 时间去重；相同标价只推进最近核验时间，变化才新增记录。 */
    fun observe(point: PricePoint): PriceHistory = when {
        point.at <= checkedAt -> this
        changes.lastOrNull()?.yuan == point.yuan -> copy(checkedAt = point.at)
        else -> copy(changes = (changes + point).takeLast(60), checkedAt = point.at)
    }
}

internal fun CardRecord.isMarketWatch() = rule.sourceId == APPLE_STORE && rule.targetKey != PRICE_RANK

/** 只有已保存的空间跟踪卡参与调度。模板试运行和浏览目录不会生成订阅。 */
internal class MarketWatch(private val context: Context) {
    private val prefs = context.getSharedPreferences("official-price-history", Context.MODE_PRIVATE)
    private val history = MutableStateFlow(Json.decodeFromString<Map<String, PriceHistory>>(prefs.getString("series", "{}")!!))
    val state = history.asStateFlow()
    private val scheduler = context.getSystemService(JobScheduler::class.java)
    private val notices = context.getSystemService(NotificationManager::class.java)

    fun sync(watched: List<CardRecord>) {
        val next = history.value.filterKeys { part -> watched.any { it.rule.targetKey == part } }.toMutableMap()
        val drops = mutableListOf<Pair<CardRecord, Pair<Int, Int>>>()
        for (card in watched.sortedBy { it.snapshot?.fetchedAt }) {
            val snapshot = card.snapshot ?: continue
            val price = snapshot.fields.firstOrNull { it.key == "price" }?.value?.removePrefix("¥")?.toIntOrNull() ?: continue
            val key = card.rule.targetKey
            val prior = next[key] ?: PriceHistory(emptyList(), 0)
            val updated = prior.observe(PricePoint(price, snapshot.fetchedAt))
            if (updated == prior) continue
            next[key] = updated
            prior.changes.lastOrNull()?.let { if (price < it.yuan) drops += card to (it.yuan to price) }
        }
        if (next != history.value) {
            for (part in history.value.keys - next.keys) notices.cancel(part, WATCH_JOB)
            saveHistory(next)
        }
        if (watched.isNotEmpty()) {
            notices.createNotificationChannel(NotificationChannel(WATCH_CHANNEL, "官网降价提醒", NotificationManager.IMPORTANCE_DEFAULT))
            if (scheduler.getPendingJob(WATCH_JOB) == null) {
                scheduler.schedule(JobInfo.Builder(WATCH_JOB, ComponentName(context, MarketPriceJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(TimeUnit.DAYS.toMillis(1)).build())
            }
        } else if (scheduler.getPendingJob(WATCH_JOB) != null) scheduler.cancel(WATCH_JOB)
        if (!notices.areNotificationsEnabled() || notices.getNotificationChannel(WATCH_CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return
        for ((card, amounts) in drops) {
            val intent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(context, WATCH_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_more).setContentTitle("${card.title} 降价")
                .setContentText("官网标价 ¥${amounts.first} → ¥${amounts.second}").setContentIntent(intent).setAutoCancel(true).build()
            notices.notify(card.rule.targetKey, WATCH_JOB, notification)
        }
    }

    /** 仅登记系统任务实际获得的新快照。手动点卡、导入旧卡和任务登记不作为后台执行证据。 */
    fun recordBackground(card: CardRecord) {
        val at = card.snapshot?.fetchedAt ?: return
        val part = card.rule.targetKey
        val series = history.value[part] ?: PriceHistory(emptyList(), 0)
        if (at in series.backgroundChecks) return
        saveHistory(history.value + (part to series.copy(backgroundChecks = (series.backgroundChecks + at).takeLast(7))))
    }

    private fun saveHistory(next: Map<String, PriceHistory>) {
        prefs.edit().putString("series", Json.encodeToString(next)).apply()
        history.value = next
    }
}

/** 系统联网周期任务复用首页点卡刷新，成功结果同时更新卡片、历史与提醒。 */
class MarketPriceJob : JobService() {
    private var running: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        running = CoroutineScope(Dispatchers.Main).launch {
            val container = (application as CardDockApplication).container
            val state = container.controller.state.first { !it.loading }
            val cards = state.cards.filter { it.isMarketWatch() }
            var retry = !state.cardsReadable
            val recordedParts = mutableSetOf<String>()
            for (card in cards) {
                val refreshed = container.controller.refreshStoredCard(card.id) { verified ->
                    // 同一轮系统任务可以刷新同一订货号的多张卡，历史中只占一次。
                    if (recordedParts.add(verified.rule.targetKey)) container.marketWatch.recordBackground(verified)
                }
                if (!refreshed) retry = true
            }
            jobFinished(params, retry)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return true
    }
}

