package com.life0423.carddock

import com.life0423.carddock.core.model.TemplateDefinition
import com.life0423.carddock.core.model.CardRecord
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

/** 替换在内置规则与最近保存的菜鸟候选之间切换，回退使用这张卡自己的上一版。 */
internal fun cainiaoRuleChange(card: CardRecord, savedCards: List<CardRecord>, rollback: Boolean): TemplateDefinition? {
    val baseline = cainiaoEmptyTemplate.rule
    if (card.rule.sourceId != baseline.sourceId || card.rule.targetKey != baseline.targetKey) return null
    val selected = when {
        rollback -> card.previousRule
        card.rule != baseline -> baseline
        else -> savedCards.lastOrNull {
            it.rule.sourceId == baseline.sourceId && it.rule.targetKey == baseline.targetKey && it.rule != baseline
        }?.rule
    }
    return selected?.let { cainiaoEmptyTemplate.copy(rule = it) }
}

/** 固定模板只定义公开字段；订单号码在试运行时单独输入。 */
internal val meituanTemplate = TemplateDefinition(
    id = "meituan-history", title = "美团历史订单", kind = TemplateKind.DELIVERY,
    description = "先在美团展开已完成订单的订单信息并复制号码。填写后开始试运行，从最近任务切回美团。",
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
