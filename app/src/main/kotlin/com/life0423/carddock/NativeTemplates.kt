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

/** 单笔订单合计来自本轮详情，订单参数留在本机，合计与实付分别表达。 */
internal val orderTotalTemplate = meituanTemplate.copy(
    id = "meituan-order-total", title = "外卖订单合计", description = "核对一笔已完成订单的合计、币种和下单时间。",
    examplePrompt = "查看美团历史订单的合计金额",
    rule = nativeRule("order-total-v1", MEITUAN_PACKAGE, "meituan.completed-order.total",
        "amount" to "订单合计", "currency" to "币种", "orderTime" to "下单时间", "status" to "订单状态"),
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

/** 公开标价榜与单配置跟踪共用已核验目录。跟踪只随空间卡启停。 */
internal val displayRankTemplate = TemplateDefinition("apple-display-rank", "官网配置价格榜",
    "Apple 中国大陆两个显示器系列，按配置标价从低到高排列。", TemplateKind.SHOPPING, "查看苹果官网显示器价格榜",
    nativeRule("apple-rank-v1", com.life0423.carddock.platform.APPLE_STORE, com.life0423.carddock.platform.PRICE_RANK,
        "ranking" to "价格最低的三个配置", "scope" to "榜单范围", "basis" to "价格来源"))

internal fun watchTemplate(offer: com.life0423.carddock.platform.DisplayOffer) = TemplateDefinition(
    "apple-watch-" + offer.part.substringBefore('/'), "${offer.model} · ${offer.part.substringBefore('/')}",
    "${offer.configuration}。添加到空间后每天核对官网标价，降价时提醒。", TemplateKind.SHOPPING,
    "跟踪 ${offer.part} 的官网标价", watchRule(offer.part))

internal fun watchRule(part: String) = nativeRule("apple-watch-v1", com.life0423.carddock.platform.APPLE_STORE, part,
    "price" to "官网标价", "variant" to "商品配置", "part" to "订货号", "basis" to "价格来源")

internal const val MONITOR_PAIR = "jd.monitor.comparison"

/** 商品范围随当前实现固定为两款。预算保留为待核价条件。 */
internal fun monitorTemplate(plan: com.life0423.carddock.core.rule.MonitorComparison): TemplateDefinition {
    val purpose = plan.focus?.label ?: plan.use.label
    val budget = plan.budgetYuan?.let { "，预算上限 ${it} 元（需另核价）" }.orEmpty()
    return TemplateDefinition("personal-monitor-pair", "${plan.use.label}显示器对比", "按${purpose}用途比较海信 GX 和华硕 ROG 的公开规格$budget。",
        TemplateKind.SHOPPING, "按${purpose}用途比较两款显示器$budget",
        nativeRule("monitor-comparison-v1", JD_PACKAGE, MONITOR_PAIR, "advice" to "选购建议",
            "hisense" to "海信 GX · 商品依据", "rog" to "华硕 ROG · 商品依据", "check" to "还需确认")
            .copy(monitorComparison = plan))
}

/** 所有指定字段均需通过非空核验后才会提交。 */
internal fun nativeRule(id: String, source: String, target: String, vararg fields: Pair<String, String>) =
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

/** 汇总卡只保留统计口径和复跑路线，订单明细只存在于当轮账本。 */
internal fun monthlyTemplate(source: String,demand: String,path: List<String>) =
    TemplateDefinition(id="personal-monthly-orders", title="近月已完成外卖实付",kind=TemplateKind.QUERY,
        description="按下单时间核对滚动一个自然月，合计退款前原实付。", examplePrompt=demand,
        rule=nativeRule("monthly-orders-v1",source,com.life0423.carddock.platform.MONTHLY_ORDERS,
            "paid" to "原实付合计（元）","orders" to "已完成外卖（单）","period" to "统计区间").copy(orderPath=path))

/** 用途或预算改变时只更新衍生的默认文案，保留用户已经命名、排序的字段和模板版本。 */
internal fun updateMonitorPlan(template: TemplateDefinition, next: com.life0423.carddock.core.rule.MonitorComparison): TemplateDefinition {
    val old = monitorTemplate(requireNotNull(template.rule.monitorComparison))
    val updated = monitorTemplate(next)
    return template.copy(title = if (template.title == old.title) updated.title else template.title,
        description = if (template.description == old.description) updated.description else template.description,
        examplePrompt = updated.examplePrompt, rule = updated.rule)
}
