package com.life0423.carddock

import android.content.Context
import android.content.Intent
import com.life0423.carddock.ai.ModelSettings
import com.life0423.carddock.ai.requestModel
import com.life0423.carddock.core.port.DeviceResponse
import com.life0423.carddock.core.port.DiscoveredTemplate
import com.life0423.carddock.core.port.DiscoveryResult
import com.life0423.carddock.core.rule.ReminderRoute
import com.life0423.carddock.platform.mika.MikaBackgroundQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.GeneralSecurityException

private val reminderDemand = Regex("""(?:请)?(?:帮我)?(?:查看|查询|显示|看看)?(?:今天|今日)?(?:的)?(?:下一个|下一条|下一次)(?:日程)?提醒的?(?:名称|标题)(?:和|与|及)(?:通知)?时间[。？！!?]?""")

/** 从设备注册的日历入口试查。模型只接收字段形态，候选值留在本机供独立重跑核对。 */
internal suspend fun discoverReminder(context: Context, settings: ModelSettings, device: MikaBackgroundQuery,
    demand: String): DiscoveryResult? = withContext(Dispatchers.IO) {
    if (!reminderDemand.matches(demand.filterNot(Char::isWhitespace))) return@withContext null
    val entry = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR)
    val sources = context.packageManager.queryIntentActivities(entry, 0).map { it.activityInfo.packageName }.distinct()
    val target = sources.singleOrNull()
        ?: return@withContext DiscoveryResult.Incomplete(if (sources.isEmpty()) "本机未找到日历入口。" else "发现多个日历来源，无法确定要核验哪一个。")
    val config = try { settings.load() } catch (_: GeneralSecurityException) { null }
        ?: return@withContext DiscoveryResult.Incomplete("请在「我的」重新保存模型配置。")
    var route: ReminderRoute? = null
    val result = try {
        device.reminderTrial(target, learn = { structure ->
            val instruction = "查看下一条提醒的名称和通知时间。从脱敏详情结构中，依次选择名称、开始时间、通知提前量三个节点。只返回以英文逗号分隔的三个节点序号。\n" + structure.joinToString("\n")
            val tokens = requestModel(config, instruction).trim().split(',')
            if (tokens.size == 3) tokens.mapNotNull { it.trim().toIntOrNull() } else emptyList()
        }, learned = { route = it })
    } catch (error: IOException) {
        return@withContext DiscoveryResult.Incomplete("模型请求失败，请检查配置后重试。" +
            error.message.orEmpty().takeIf { Regex("HTTP [0-9]{3}").matches(it) }.orEmpty())
    }
    when (result) {
        is DeviceResponse.Ready -> DiscoveryResult.Found(DiscoveredTemplate(
            reminderTemplate(target, demand, checkNotNull(route)), result.snapshot.fields))
        is DeviceResponse.NeedsUser -> DiscoveryResult.Incomplete(result.message)
        is DeviceResponse.Failed -> DiscoveryResult.Incomplete(result.message)
    }
}
