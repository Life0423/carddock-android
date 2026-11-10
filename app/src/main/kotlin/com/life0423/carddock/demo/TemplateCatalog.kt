package com.life0423.carddock.demo

import com.life0423.carddock.core.model.TemplateDefinition
import com.life0423.carddock.core.model.TemplateKind
import com.life0423.carddock.core.rule.RuleSpec
import com.life0423.carddock.core.rule.RuleStep

internal const val EXPRESS_SOURCE = "demo.express"
internal const val DELIVERY_SOURCE = "demo.delivery"
internal const val TRAVEL_SOURCE = "demo.travel"
internal const val EXPRESS_TARGET = "parcel-demo-001"
internal const val DELIVERY_TARGET = "delivery-demo-001"
internal const val TRAVEL_TARGET = "journey-demo-001"

/**
 * Small, reviewed examples. Each rule reads exactly one stable demonstration object.
 * 每条规则都是先核对固定目标，再对每个字段读取并要求非空。字段名与 DemoDeviceGateway 的演示页一致。
 */
object TemplateCatalog {
    val templates: List<TemplateDefinition> = listOf(
        TemplateDefinition(
            id = "express-pickup",
            title = "待取快递",
            description = "把取件码、取件位置和包裹状态放到一张卡片里。",
            kind = TemplateKind.EXPRESS,
            examplePrompt = "查看我的快递取件码和取件位置",
            rule = rule(
                id = "express-pickup",
                sourceId = EXPRESS_SOURCE,
                targetKey = EXPRESS_TARGET,
                fields = listOf("pickupCode" to "取件码", "location" to "取件位置", "status" to "包裹状态"),
            ),
        ),
        TemplateDefinition(
            id = "delivery-status",
            title = "外卖进度",
            description = "汇总配送状态、预计送达时间和餐品信息。",
            kind = TemplateKind.DELIVERY,
            examplePrompt = "查看我的外卖配送状态和预计送达时间",
            rule = rule(
                id = "delivery-status",
                sourceId = DELIVERY_SOURCE,
                targetKey = DELIVERY_TARGET,
                fields = listOf("status" to "配送状态", "arrival" to "预计送达", "meal" to "餐品"),
            ),
        ),
        TemplateDefinition(
            id = "travel-overview",
            title = "出行安排",
            description = "随时查看这一程的路线、出发时间和检票位置。",
            kind = TemplateKind.TRAVEL,
            examplePrompt = "查看我的出行路线、出发时间和检票口",
            rule = rule(
                id = "travel-overview",
                sourceId = TRAVEL_SOURCE,
                targetKey = TRAVEL_TARGET,
                fields = listOf("route" to "行程路线", "departure" to "出发时间", "gate" to "检票口"),
            ),
        ),
    )

    private fun rule(
        id: String,
        sourceId: String,
        targetKey: String,
        fields: List<Pair<String, String>>,
    ) = RuleSpec(
        id = id,
        sourceId = sourceId,
        targetKey = targetKey,
        steps = buildList {
            add(RuleStep.AssertTarget(targetKey))
            for ((key, label) in fields) {
                add(RuleStep.ReadField(sourceField = key, outputKey = key, label = label))
                add(RuleStep.RequireField(outputKey = key))
            }
        },
    )
}
