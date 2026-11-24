package com.life0423.carddock.ai

import com.life0423.carddock.core.model.TemplateDefinition
import com.life0423.carddock.core.model.TemplateKind
import com.life0423.carddock.core.port.DiscoveryResult
import com.life0423.carddock.core.rule.MealTarget
import com.life0423.carddock.core.rule.OfficialAction
import com.life0423.carddock.nativeRule

internal const val MCD_PACKAGE = "com.mcdonalds.gma.cn"
internal const val MEAL_TITLE = "炸鸡随心配1+1"
internal const val CART_MENU_PROMPT = "在麦当劳把我选的套餐加入购物车"
internal const val CONE = "灰焰圆筒"

/** 书名号内是用户给的商品名；特定门店可用全称填写，其他限制交由需求规划器处理。 */
private val mealRequest = Regex("""(?:请|帮我|我想)?(?:打开|去)?麦当劳(?:[（(]([^（）()]+餐厅)[）)])?[，,\s]*(?:选择|点|选|来|买)?(?:一份)?(?:「([^「」]+)」|(炸鸡随心配1\+1|随心选套餐|随心配套餐|灰焰圆筒))(?:[，,\s]*(?:并|再|然后|并且)?(?:加入|放入|加到)(?:我的)?购物车)?[。！!]?""")

internal fun explicitMeal(prompt: String): MealTarget? = mealRequest.matchEntire(prompt)?.destructured?.let { (store, named, shorthand) ->
    MealTarget(named.ifBlank { if (shorthand in setOf("随心选套餐", "随心配套餐")) MEAL_TITLE else shorthand }, store.ifBlank { null })
}

/** 只读需求复用同一套门店取证，不附带官方跳转或购物车动作。 */
internal fun mealStatusTemplate(prompt: String, meal: MealTarget): TemplateDefinition {
    val id = "meal-status"
    return TemplateDefinition(id,meal.product,"后台核对${meal.store ?: "当前门店"}的即时菜单，返回商品规格。",TemplateKind.SHOPPING,prompt,
        nativeRule(id,MCD_PACKAGE,meal.product,"entry" to "商品","store" to "即时门店","options" to "页面所列规格").copy(meal=meal))
}

/** 固定搭配只复核同店商品及所选分组；首次试查返回当前选项供用户确认。 */
internal fun fixedMealTemplate(prompt: String, meal: MealTarget): TemplateDefinition {
    val id = "meal-repeat_meal"
    val rule = nativeRule(id, MCD_PACKAGE, OfficialAction.REPEAT_MEAL.name,
        "pair" to "固定搭配", "entry" to "商品", "store" to "即时门店", "options" to "本次可见选项")
    return TemplateDefinition(id, "${meal.product} · 常用搭配",
        "只读核对${meal.store ?: "当前到店取餐门店"}的即时供应和可选搭配。缺少商品或选项时可保存草稿。",
        TemplateKind.SHOPPING, prompt, rule.copy(officialAction = OfficialAction.REPEAT_MEAL, meal = meal))
}

internal const val EXPLORE_MEAL = "mika-menu-preview"

/** 探索方案只承诺本轮核对，门店与分组来自试查时的官方页面。 */
internal fun explorationPlan(prompt: String, meal: MealTarget): DiscoveryResult.Plans {
    val fields = mutableListOf("entry" to "商品", "store" to "门店", "options" to "本次可见分组")
    if (meal.optionHints.isNotEmpty()) fields += "matches" to "配餐核对"
    val rule = nativeRule(EXPLORE_MEAL, MCD_PACKAGE, EXPLORE_MEAL, *fields.toTypedArray())
    val title = if (meal.searchByDescription) "查找相近商品" else meal.product
    val plan = TemplateDefinition(EXPLORE_MEAL, title, "使用已配置的模型核对官方商品页和分组。",
        TemplateKind.SHOPPING, prompt, rule.copy(meal = meal))
    return DiscoveryResult.Plans(listOf(plan), "${meal.store ?: "当前到店取餐门店"} · $title。将${if (meal.searchByDescription) "脱敏需求" else "商品名"}和可见菜单候选发送给你的模型，只核对商品页和分组。")
}

/** 可复用的加购模板。试运行和刷新只核对配餐，写入由独立确认按钮触发。 */
internal fun cartPlan(prompt: String, meal: MealTarget): DiscoveryResult.Plans {
    val action = OfficialAction.ADD_MEAL
    val rule = nativeRule("meal-cart", MCD_PACKAGE, action.name, "pair" to "已选搭配", "entry" to "官方商品",
        "store" to "即时门店", "options" to "核对范围").copy(officialAction = action, meal = meal)
    val template = TemplateDefinition("meal-cart", if (meal.searchByDescription) "套餐加购" else "${meal.product} · 加购",
        "使用官方当前规格，在卡坞选定单选配餐。可保存复用，每次加购前核对门店、搭配与价格。",
        TemplateKind.SHOPPING, prompt, rule)
    return DiscoveryResult.Plans(listOf(template), "在${meal.store ?: "当前门店"}查找套餐，将商品线索和菜单候选发给你的模型。先核对搭配，确认加购时加入 1 份。")
}

/** 入口、选商品、复用搭配须分别确认；缩小执行范围由用户选择。 */
internal fun mealPlans(prompt: String, meal: MealTarget): DiscoveryResult.Plans {
    if (meal.product !in setOf(CONE, MEAL_TITLE)) return explorationPlan(prompt, meal)
    fun choice(action: OfficialAction, title: String, description: String): TemplateDefinition {
        val detail = action == OfficialAction.SELECT_MEAL
        val target = if (detail) meal.product else action.name
        val id = "meal-" + action.name.lowercase()
        val rule = if (detail) mealStatusTemplate(prompt, meal).rule.copy(id = id)
            else nativeRule(id, MCD_PACKAGE, target, "entry" to "官方入口")
        return TemplateDefinition(id, title, description, TemplateKind.SHOPPING, prompt,
            rule.copy(officialAction = action, meal = meal.takeUnless { action == OfficialAction.OPEN_MCDONALDS }))
    }
    val where = meal.store ?: "当前到店取餐门店"
    return DiscoveryResult.Plans(listOf(
        choice(OfficialAction.SELECT_MEAL, meal.product, "打开${where}的「${meal.product}」规格页，由你确认选项和加购。"),
        fixedMealTemplate(prompt, meal),
        choice(OfficialAction.OPEN_MCDONALDS, "先只打开麦当劳", "打开麦当劳官方 App，门店和商品由你选择。"),
    ), "${where} · ${meal.product}，要怎么打开？")
}
