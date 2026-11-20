package com.life0423.carddock

import com.life0423.carddock.platform.CAINIAO_STATUS_TARGET
import com.life0423.carddock.core.rule.RuleStep
import com.life0423.carddock.core.model.PickupEntry
import com.life0423.carddock.core.port.DeviceResponse
import com.life0423.carddock.core.port.DiscoveredTemplate
import com.life0423.carddock.core.port.DiscoveryResult
import com.life0423.carddock.platform.mika.MikaBackgroundQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 完整匹配本人的查询和出示意图，附加转发、付款等要求留作草稿。 */
internal val pickupDemand = Regex(
    """(?:请)?(?:帮我|我想要?|给我)?(?:查询?|查看|看看?|有没有|有哪些)(?:一下)?(?:我(?:的|有哪些|有没有)?)?(?:菜鸟(?:里|的)?)?(?:待取(?:的)?)?(?:快递|包裹)(?:状态)?[，,、。\s]*(?:并且?|然后|再|和)?(?:到|在)?(?:驿站|取件时|取件|取快递)?(?:时|后)?(?:可以|能)?(?:一键|直接)?(?:(?:出示|打开|展示|显示|亮出)(?:菜鸟的?|我的?|本人)?(?:取件|驿站)?(?:身份码|出库码)|亮码)[。！!\s]*"""
)

/** 先从菜鸟首页取得可见入口和实际包裹状态，再把发现的动作交给独立试运行。 */
internal suspend fun discoverPickup(device: MikaBackgroundQuery, demand: String): DiscoveryResult? {
    if (listOf("身份码","出库码","亮码").none(demand::contains) ||
        listOf("快递","包裹","取件","驿站","菜鸟").none(demand::contains)) return null
    if ("淘宝" in demand) return DiscoveryResult.Incomplete("淘宝的亮码入口还未核验，可保留需求草稿。")
    if (!pickupDemand.matches(demand.trim()))
        return DiscoveryResult.Incomplete("可描述为“查快递，到驿站时出示取件身份码”。其他动作可保留为草稿。")
    return withContext(Dispatchers.IO) {
        fun issue(result: DeviceResponse) = DiscoveryResult.Incomplete(when (result) {
            is DeviceResponse.NeedsUser -> result.message
            is DeviceResponse.Failed -> result.message
            is DeviceResponse.Ready -> "未能找到可独立核验的入口。"
        })
        val navigation = device.pickupCode(null)
        if (navigation !is DeviceResponse.Ready) return@withContext issue(navigation)
        val entries = PickupEntry.entries.filter { it.name in navigation.snapshot.fields }
        if (entries.isEmpty()) return@withContext issue(navigation)
        val key = CAINIAO_STATUS_TARGET
        val base = cainiaoEmptyTemplate.copy(rule=cainiaoEmptyTemplate.rule.copy(id="cainiao-pickup-status-v1",
            targetKey=key, steps=cainiaoEmptyTemplate.rule.steps.map {
                if (it is RuleStep.AssertTarget)
                    RuleStep.AssertTarget(key) else it
            }))
        val query = device.cainiao(base.rule.sourceId,base.rule.targetKey)
        if (query !is DeviceResponse.Ready) return@withContext issue(query)
        val choices = entries.map { entry ->
            DiscoveredTemplate(base.copy(id="personal-pickup", title="快递取件", examplePrompt=demand,
                description="点卡查询待取状态。出示身份码后，可从系统最近任务返回卡坞。", pickupEntry=entry),query.snapshot.fields)
        }
        if (choices.size > 1) return@withContext DiscoveryResult.Demonstrate(choices)
        DiscoveryResult.Found(choices.single())
    }
}
