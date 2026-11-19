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
    description = "核验菜鸟取包裹页的明确空状态。启用页面读取并连接 Mika 助手后，试运行会在后台打开并核验取包裹页。",
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
    description = "选择本机已核验的历史订单，查看订单状态和下单时间。",
    examplePrompt = "核验美团历史订单",
    rule = nativeRule("meituan-history-v1", MEITUAN_PACKAGE, MEITUAN_TARGET,
        "status" to "订单状态", "orderTime" to "下单时间"),
    requiresOrderNumber = true,
)

/** 最近外卖按列表下单时间选取，试运行与刷新均无需录入本机历史订单参数。 */
internal val latestTakeoutTemplate = TemplateDefinition(
    id = "meituan-latest", title = "美团最近外卖", kind = TemplateKind.DELIVERY,
    description = "核验近一个月的外卖。当前支持完整一屏的已完成订单列表。",
    examplePrompt = "查看美团最近一笔外卖",
    rule = nativeRule("meituan-latest-v1", MEITUAN_PACKAGE, com.life0423.carddock.platform.MEITUAN_LATEST,
        "status" to "订单状态", "orderTime" to "下单时间"),
)

/** 展示字段可少于页面核验所需信息，订单身份仍由执行器独立检查。 */
internal val meituanStatusTemplate = meituanTemplate.copy(
    id = "meituan-status",
    title = "美团订单状态",
    description = "核验授权订单后，仅显示订单状态。",
    examplePrompt = "只显示美团订单状态",
    rule = nativeRule("meituan-status-v1", MEITUAN_PACKAGE, MEITUAN_TARGET, "status" to "订单状态"),
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


/** 个人模板经独立重跑核验，来源与结构锚点随模板及卡片保存。 */
internal fun reminderTemplate(source: String, demand: String, route: com.life0423.carddock.core.rule.ReminderRoute) =
    TemplateDefinition(id = "personal-reminder", title = "今日可见提醒", kind = TemplateKind.REMINDER,
        description = "查看今日列表中可核验的下一次通知", examplePrompt = demand,
        rule = nativeRule("reminder-route-v1", source, "visible-reminder", "名称" to "名称", "通知时间" to "通知时间").copy(reminderRoute = route))

/** 个人数量卡只保存用户指定的分类和字段位置。 */
internal fun countTemplate(source: String, demand: String, route: com.life0423.carddock.core.rule.CountRoute) =
    TemplateDefinition(id = "personal-count", title = route.subject + "数量", kind = TemplateKind.QUERY,
        description = "核对" + (route.page?.plus("页中的") ?: "首页的") + route.subject + "数量", examplePrompt = demand,
        rule = nativeRule("visible-count-v1", source, com.life0423.carddock.platform.COUNT_TARGET, "quantity" to "数量").copy(countRoute = route))
