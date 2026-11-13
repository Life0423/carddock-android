package com.life0423.carddock

import com.life0423.carddock.core.model.TemplateDefinition
import com.life0423.carddock.core.model.TemplateKind
import com.life0423.carddock.core.rule.RuleSpec
import com.life0423.carddock.core.rule.RuleStep
import com.life0423.carddock.platform.JdProduct
import com.life0423.carddock.platform.JD_PACKAGE
import com.life0423.carddock.platform.MEITUAN_PACKAGE
import com.life0423.carddock.platform.MEITUAN_TARGET

/** 首个真机模板仅证明明确空状态，不把未发现取件码当作没有包裹。 */
val cainiaoEmptyTemplate = TemplateDefinition(
    id = "cainiao-empty", title = "菜鸟待取 · 真机", kind = TemplateKind.EXPRESS,
    description = "核验菜鸟取包裹页的明确空状态。先在我的中启用页面读取，试运行后 20 秒内手动切到目标页，再返回确认保存。",
    examplePrompt = "核验菜鸟暂无待取包裹",
    rule = RuleSpec(
        id = "cainiao-empty-v1", sourceId = "com.cainiao.wireless", targetKey = "cainiao.pickup.empty",
        steps = listOf(
            RuleStep.AssertTarget("cainiao.pickup.empty"),
            RuleStep.ReadField("status", "status", "待取状态"),
            RuleStep.RequireField("status"),
        ),
    ),
)

/** 固定模板只定义公开字段；订单号码在试运行时单独输入。 */
internal val meituanTemplate = TemplateDefinition(
    id = "meituan-history", title = "美团历史订单", kind = TemplateKind.DELIVERY,
    description = "填写本机订单号码，开始后手动打开已完成订单并展开订单信息。",
    examplePrompt = "核验美团历史订单",
    rule = nativeRule("meituan-history-v1", MEITUAN_PACKAGE, MEITUAN_TARGET,
        "status" to "订单状态", "orderTime" to "下单时间"),
    requiresOrderNumber = true,
)

internal val jdTemplates = JdProduct.entries.map { product ->
    TemplateDefinition(
        id = "jd-${product.name.lowercase()}", title = product.title, kind = TemplateKind.SHOPPING,
        description = "公开核验 ${product.sku} · ${product.variant}。保存后可单独核验 App 展示价。",
        examplePrompt = "核验京东商品 ${product.name}",
        rule = nativeRule("jd-${product.name.lowercase()}-v1", JD_PACKAGE, product.sku,
            "sku" to "SKU", "variant" to "规格", "title" to "商品名称"),
    )
}

/** 所有指定字段均需通过非空核验后才会提交。 */
private fun nativeRule(id: String, source: String, target: String, vararg fields: Pair<String, String>) =
    RuleSpec(id = id, sourceId = source, targetKey = target, steps = buildList {
        add(RuleStep.AssertTarget(target))
        fields.forEach { (key, label) ->
            add(RuleStep.ReadField(key, key, label))
            add(RuleStep.RequireField(key))
        }
    })
