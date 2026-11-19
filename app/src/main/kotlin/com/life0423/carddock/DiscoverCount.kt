package com.life0423.carddock

import android.content.Context
import android.content.Intent
import com.life0423.carddock.ai.ModelSettings
import com.life0423.carddock.ai.requestModel
import com.life0423.carddock.core.port.DeviceResponse
import com.life0423.carddock.core.port.DiscoveredTemplate
import com.life0423.carddock.core.port.DiscoveryResult
import com.life0423.carddock.platform.CountField
import com.life0423.carddock.platform.mika.MikaBackgroundQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.GeneralSecurityException

private val countRequest = Regex("""(?:的|里|中)?(?:[「“]?([^「」“”]+?)[」”]?页(?:面)?(?:里|中|的)?)?[「“]?([^「」“”]+?)[」”]?(?:的)?(?:数量|个数|总数)[。？！!?]?""")

/** 按用户在需求中点名的应用匹配启动入口；页面和分类名称均来自用户输入。 */
internal suspend fun discoverCount(context: Context, settings: ModelSettings, device: MikaBackgroundQuery,
    demand: String): DiscoveryResult? = withContext(Dispatchers.IO) {
    val text = demand.trim().replaceFirst(Regex("^(?:请)?(?:帮我)?(?:查看|查询|显示|看看)"), "")
    if (!text.trimEnd('。','？','！','!','?').endsWith("数") && !text.trimEnd('。','？','！','!','?').endsWith("数量")) return@withContext null
    val pm = context.packageManager
    val matches = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        .filter { (label, pkg) -> label.isNotBlank() && text.startsWith(label) && pkg != context.packageName }.distinct()
    val label = matches.maxByOrNull { it.first.length }?.first
        ?: return@withContext DiscoveryResult.Incomplete("请在需求中写出手机上的应用名称、页签和要查看数量的分类。")
    val pkg = matches.singleOrNull { it.first == label }?.second
        ?: return@withContext DiscoveryResult.Incomplete("本机有多个同名应用，无法唯一确定来源。")
    val parsed = countRequest.matchEntire(text.removePrefix(label)) ?: return@withContext null
    val page = parsed.groupValues[1].takeIf(String::isNotBlank)
    val subject = parsed.groupValues[2]
    val config = try { settings.load() } catch (_: GeneralSecurityException) { null }
        ?: return@withContext DiscoveryResult.Incomplete("请在「我的」保存模型配置。")
    var picked: CountField? = null
    var demonstration = emptyList<DiscoveredTemplate>()
    fun candidate(field: CountField) = DiscoveredTemplate(countTemplate(pkg, demand, field.route), mapOf("quantity" to field.value))
    val result = try {
        device.count(pkg, page, subject) { fields, shape ->
            val answer = requestModel(config, "用户要查询一个分类的数量。以下为脱敏结构。requested-label 是精确匹配，partial-label 只匹配了部分名称。仅在精确且唯一时返回数量节点序号。多个可能分类或字段时返回 -1。\n$shape")
            // 模型不能替用户决定部分匹配的分类，即使两个候选当前的数值不同。
            picked = fields.singleOrNull()?.takeIf { it.route.subject == subject && it.index == answer.trim().toIntOrNull() }
            if (picked == null && fields.distinctBy { it.route }.size == fields.size)
                demonstration = fields.map(::candidate)
            picked
        }
    } catch (_: IOException) {
        return@withContext DiscoveryResult.Incomplete("模型请求失败，请检查网络和配置后重试。")
    }
    when (result) {
        is DeviceResponse.Ready -> DiscoveryResult.Found(candidate(checkNotNull(picked)))
        is DeviceResponse.NeedsUser -> if (demonstration.isEmpty()) DiscoveryResult.Incomplete(result.message)
            else DiscoveryResult.Demonstrate(demonstration)
        is DeviceResponse.Failed -> DiscoveryResult.Incomplete(result.message)
    }
}
