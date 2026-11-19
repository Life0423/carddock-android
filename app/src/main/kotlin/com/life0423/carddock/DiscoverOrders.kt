package com.life0423.carddock

import android.content.Context
import android.content.Intent
import com.life0423.carddock.ai.ModelSettings
import com.life0423.carddock.ai.requestModel
import com.life0423.carddock.core.port.*
import com.life0423.carddock.platform.OrderCollection
import com.life0423.carddock.platform.mika.MikaBackgroundQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/** 按已确认的统计口径分流，来源由用户在需求中点名。模型只看到可选的只读入口名称。 */
internal suspend fun discoverOrders(context: Context, settings: ModelSettings, device: MikaBackgroundQuery,
    demand: String, compareFilter: Boolean): DiscoveryResult? = withContext(Dispatchers.IO) {
    val goal = Regex("""统计最近一个月(?:的)?已完成外卖订单的(?:退款前原实付|原实付|总金额)(?:合计)?[。]?""")
    val pm = context.packageManager
    val sources = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)
        .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }.distinct()
    val matches = sources.filter { (name, _) -> name.isNotBlank() && (demand.startsWith(name) || demand.startsWith("用$name")) &&
        goal.matches(demand.removePrefix("用").removePrefix(name).trim()) }
    if (matches.isEmpty()) return@withContext if (goal.matches(demand)) DiscoveryResult.Incomplete("请在需求前写出来源应用，如“用〈应用名〉统计最近一个月已完成外卖订单的总金额”。") else null
    val source = matches.singleOrNull()?.second ?: return@withContext DiscoveryResult.Incomplete("有多个同名来源，请核对应用。")
    val config = settings.load() ?: return@withContext DiscoveryResult.Incomplete("请先保存模型配置。")
    val cutoff = System.currentTimeMillis()
    val scan = OrderCollection(source,Instant.ofEpochMilli(cutoff).atZone(ZoneId.systemDefault()),null,compareFilter)
    val result = device.monthlyOrders(source,scan) { options ->
        val msg = "目标：从当前页面进入所有历史订单的集合。如果当前只有个人中心入口，先进入个人中心。选一个可见只读入口，以所给的完整名称回答；不能判断返回 ?。候选：" + options.joinToString("、")
        requestModel(config,msg).trim().takeIf { it in options }
    }
    when (result) {
        is DeviceResponse.Ready -> DiscoveryResult.Found(DiscoveredTemplate(monthlyTemplate(source,demand,scan.steps.toList()),
            result.snapshot.fields,cutoff,scan.digest))
        is DeviceResponse.NeedsUser -> DiscoveryResult.Incomplete(result.message)
        is DeviceResponse.Failed -> DiscoveryResult.Incomplete(result.message)
    }
}
