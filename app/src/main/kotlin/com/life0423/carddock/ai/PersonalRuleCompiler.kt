package com.life0423.carddock.ai

import com.life0423.carddock.cainiaoEmptyTemplate
import com.life0423.carddock.core.model.DisplayField
import com.life0423.carddock.core.port.CompileResult
import com.life0423.carddock.core.port.RuleCompiler
import com.life0423.carddock.jdTemplates
import com.life0423.carddock.meituanStatusTemplate
import com.life0423.carddock.latestTakeoutTemplate
import com.life0423.carddock.orderTotalTemplate
import com.life0423.carddock.meituanTemplate

/** 将本地支持的只读意图与展示字段编译为候选。整句匹配避免丢掉用户需求中的附加条件。 */
internal object PersonalRuleCompiler : RuleCompiler {
    private val polite = Regex("^(?:请|麻烦)?(?:帮我|我想要|我想|给我)?(?:查一下|看一下|看看|查查|查看|查询|核验|检查|显示|展示)?")
    private val pickup = Regex("菜鸟(?:里|里面|的)?(?:有没有|有无|是否有|还有没有|暂无)?(?:待取|待取件|待领取)(?:的)?(?:包裹|快递)(?:的)?(?:状态|吗)?")
    // “查看菜鸟待取包裹”沿用状态查询。显式打开、指定对象或命名目的页才进入导航。
    private val pickupDestination = Regex(
        """(?:请|麻烦)?(?:帮我|我想要?|给我)?(?:(?:在菜鸟(?:中|里)?打开|打开菜鸟(?:的)?)(?:我(?:选的|选择的|的))?待取(?:包裹|快递)(?:详情|列表)?|(?:在菜鸟(?:中|里)?(?:查看|打开)|(?:打开|查看|进入|直达)菜鸟(?:的)?)(?:我(?:选的|选择的|的))?(?:待取)?(?:包裹|快递)(?:列表|详情)|(?:打开|查看|进入|直达)菜鸟待取列表)"""
    )
    private val orderStatus = Regex("(?:(?:只|仅)(?:想看|显示|展示|查看)美团(?:历史)?(?:外卖)?订单(?:的)?状态|美团(?:历史)?(?:外卖)?订单(?:只|仅)(?:显示|展示)(?:订单)?状态)")
    private val order = Regex("美团(?:历史|已完成)(?:外卖)?订单(?:的)?(.*)")
    private val product = Regex("京东(?:的)?商品([ab])(?:的)?(.*)")
    private val separators = Regex("(?:、|，|,|以及|和|及|与|然后|再显示|再看)")
    private val orderFields = mapOf("状态" to DisplayField("status", "订单状态"), "订单状态" to DisplayField("status", "订单状态"),
        "下单时间" to DisplayField("orderTime", "下单时间"))
    private val productFields = mapOf("规格" to DisplayField("variant", "规格"), "型号" to DisplayField("variant", "规格"),
        "名称" to DisplayField("title", "商品名称"), "商品名称" to DisplayField("title", "商品名称"),
        "sku" to DisplayField("sku", "SKU"))

    override suspend fun compile(prompt: String): CompileResult {
        com.life0423.carddock.core.rule.parseLocalReminder(prompt, java.time.ZonedDateTime.now())?.let { schedule ->
            val rule = com.life0423.carddock.nativeRule("local-reminder", "carddock.reminder", "scheduled", "subject" to "提醒事项", "time" to "计划时间")
            return CompileResult.Success(com.life0423.carddock.core.model.TemplateDefinition("local-reminder",schedule.subject,
                "加入空间后按手机本地时间提醒；试运行只核对设置。", com.life0423.carddock.core.model.TemplateKind.REMINDER, prompt,
                rule.copy(localReminder = schedule)))
        }
        val namedMeal = Regex("""(?:请|帮我)?(?:查看|查询|看看|核对)麦当劳(?:[（(]([^（）()]+餐厅)[）)])?「([^「」]+)」(?:的|现在有哪些)?(规格|固定搭配)[？?。]?""").matchEntire(prompt.trim())
        if (namedMeal != null) {
            val meal = com.life0423.carddock.core.rule.MealTarget(namedMeal.groupValues[2], namedMeal.groupValues[1].ifBlank { null })
            if (meal.product !in setOf(CONE, MEAL_TITLE)) return unsupported()
            return CompileResult.Success(if (namedMeal.groupValues[3] == "固定搭配") fixedMealTemplate(prompt, meal) else mealStatusTemplate(prompt, meal))
        }
        val request = prompt.trim().lowercase()
        val exact = (listOf(cainiaoEmptyTemplate, meituanTemplate, meituanStatusTemplate, orderTotalTemplate, latestTakeoutTemplate) + jdTemplates)
            .firstOrNull { it.examplePrompt.lowercase() == request }
        if (exact != null) return CompileResult.Success(exact)
        val text = request.replace(Regex("\\s+"), "").trimEnd('。', '！', '!', '.', '？', '?').replaceFirst(polite, "")
        if (Regex("美团(?:的)?(?:最近|最新)(?:一笔|一单)?外卖(?:订单)?(?:的)?(?:状态)?").matches(text)) return CompileResult.Success(latestTakeoutTemplate)
        if (pickupDestination.matches(request.filterNot(Char::isWhitespace).trimEnd('。', '！', '!', '.', '？', '?'))) {
            return CompileResult.Success(com.life0423.carddock.pickupStatusTemplate.copy(
                id = "personal-cainiao-pickup-list", title = "菜鸟待取列表",
                description = "进入菜鸟后选择包裹", examplePrompt = "打开菜鸟待取列表", opensPickupList = true,
                rule = com.life0423.carddock.pickupStatusTemplate.rule.copy(officialAction = com.life0423.carddock.core.rule.OfficialAction.PICKUP_LIST)))
        }
        if (pickup.matches(text)) return CompileResult.Success(
            if ("暂无" in text) cainiaoEmptyTemplate else com.life0423.carddock.pickupStatusTemplate)
        if (orderStatus.matches(text) || request.trimEnd('.', '!') == "meituan order status only")
            return CompileResult.Success(meituanStatusTemplate)
        val jd = product.matchEntire(text)
        val mt = order.matchEntire(text)
        val template = when {
            jd != null -> jdTemplates.first { it.id == "jd-${jd.groupValues[1]}" }
            mt != null -> meituanTemplate
            else -> return unsupported()
        }
        val fieldRequest = (jd?.groupValues?.get(2) ?: mt!!.groupValues[1])
            .trimStart('，', ',').replaceFirst(Regex("^(?:只|仅)?(?:显示|展示|查看|看)?"), "")
        if (mt != null && fieldRequest in setOf("合计", "订单合计", "合计金额")) return CompileResult.Success(orderTotalTemplate)
        if (fieldRequest.isEmpty()) return CompileResult.Success(template)
        val allowed = if (jd != null) productFields else orderFields
        val fields = fieldRequest.split(separators).map { allowed[it] ?: return unsupported() }.distinctBy { it.key }
        return CompileResult.Success(template.copy(display = fields))
    }

    private fun unsupported() = CompileResult.Unsupported("当前支持菜鸟待取空状态、美团最近外卖、美团历史订单和京东商品 A/B。可以描述想看的字段，或保存需求草稿。")
}
