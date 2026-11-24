package com.life0423.carddock.platform

import com.life0423.carddock.ai.*
import com.life0423.carddock.core.rule.MealTarget
import org.junit.Assert.*
import org.junit.Test

/** 以官方菜单实际结构区分商品行、规格、菜单不可见和已确认的终止原因。 */
class MealPageTest {
    private fun row(parent: Int, id: String, text: String) =
        DetailNode(parent, "$MCD_PACKAGE:id/$id", null, text, true)
    private fun page(vararg rows: DetailNode) = MealPage(DetailWindow(MCD_PACKAGE, rows.toList(), false))

    @Test fun cartReceiptCountsOnlyMatchingRowsAndOfficialBagBadge() {
        val target = MealTarget("套餐", "门店", mapOf("饮品" to "中杯怡泉+C", "小食" to "薯条"))
        val rows = listOf(
            row(-1, "layout_select_standards", ""), row(0, "tv_tip_number", "8"),
            row(-1, "cl_tip_number", ""), row(2, "tv_tip_number", "3"),
            row(-1, "rc_car_list", ""), row(4, "root_view", ""), row(5, "tv_product_name", "套餐"),
            row(5, "tv_customize_info", "薯条+可乐"), row(5, "tv_count", "2"),
            row(4, "root_view", ""), row(9, "tv_product_name", "套餐"), row(9, "tv_customize_info", "薯条+中杯怡泉+C"),
            row(9, "addwidget", ""), row(12, "tv_count", "1"))
        val bag = DetailWindow(MCD_PACKAGE, rows, false)
        assertEquals(3, menuCartCount(bag))
        assertEquals(1, cartSelectionCount(bag, target, 3))
        assertEquals(0, cartSelectionCount(bag, target.copy(choices = mapOf("饮品" to "中杯怡泉", "小食" to "薯条")), 3))
        assertNull(cartSelectionCount(bag, target, 4))
        val menu = bag.copy(nodes = rows.take(2))
        assertNull(menuCartCount(menu))
        assertNull(cartSelectionCount(menu, target, 1))
        assertEquals(0, menuCartCount(menu.copy(nodes = listOf(row(-1, "tv_total_price", "未选购餐品")))))
    }

    @Test fun cartCannotAttributeSharedOptionToOneGroup() {
        val selected = MealTarget("套餐", "门店", mapOf("小食" to "玉米", "饮品" to "果汁"))
        assertTrue(ambiguousMealChoices(selected, mapOf("小食" to listOf("玉米"), "饮品" to listOf("玉米", "果汁"))))
        assertFalse(ambiguousMealChoices(selected, mapOf("小食" to listOf("玉米"), "饮品" to listOf("果汁"))))
    }

    @Test fun onlyOfficialPeriodCountdownCanBeDismissed() {
        fun popup(message: String) = page(row(-1, "rl_dialog_bg", ""), row(0, "tv_message", message), row(0, "buttons", ""), row(2, "tv_confirm", "确定"))
        assertEquals("确定", popup("下午茶距结束还剩2分钟").periodDismiss?.label)
        assertNull(popup("确认提交订单？").periodDismiss)
        assertNull(page(row(-1, "rl_dialog_bg", ""), row(0, "tv_message", "午餐距结束还剩2分钟"),
            row(-1, "other_dialog", ""), row(2, "tv_confirm", "确定")).periodDismiss)
        assertNull(page(row(-1, "other", "午餐距结束还剩2分钟"), row(-1, "tv_confirm", "确定")).periodDismiss)
    }

    @Test fun addNeedsCurrentExactCompositionQuantityAndPrice() {
        fun detail(qty: String) = page(row(-1,"ll_product_title",""),row(0,"tv_product_name","午餐"),
            row(-1,"tv_product_label_name","小食"),row(-1,"tv_product_label_name","橙汁"),row(-1,"tv_count",qty),
            row(-1,"tv_add_cart","¥ 29.9  加入购物车"))
        val target=MealTarget("午餐","测试店",mapOf("小吃" to "小食","饮料" to "橙汁"))
        assertTrue(detail("1").matchesCart(target,"29.9"))
        assertFalse(detail("2").matchesCart(target,"29.9"))
        assertFalse(detail("1").matchesCart(target,"25"))
        assertFalse(detail("1").matchesCart(target.copy(choices=mapOf("小吃" to "小食")),"29.9"))
        val prompt="把麦当劳的午餐加入购物车"
        val t=(mealProposal(PlanAnswer("mcd_cart",product="午餐"),prompt,prompt) as com.life0423.carddock.core.port.DiscoveryResult.Plans).choices.single()
        assertEquals(com.life0423.carddock.core.rule.OfficialAction.ADD_MEAL,t.rule.officialAction)
        val denied = "麦当劳的午餐别加入购物车"
        assertNull((mealProposal(PlanAnswer("mcd_cart"),denied,denied) as com.life0423.carddock.core.port.DiscoveryResult.Plans).choices.single().rule.officialAction)
        val onlyRead = "只想看看麦当劳的套餐，不要把它加入购物车"
        assertNull((mealProposal(PlanAnswer("mcd_cart"), onlyRead, onlyRead) as com.life0423.carddock.core.port.DiscoveryResult.Plans).choices.single().rule.officialAction)
        assertNull(mealProposal(PlanAnswer("unsupported", missing = "action"), prompt, prompt))
        val multiple = "麦当劳两个套餐加入购物车"
        assertTrue(mealProposal(PlanAnswer("mcd_cart"), multiple, multiple) is com.life0423.carddock.core.port.DiscoveryResult.Incomplete)
        val two = "麦当劳套餐加入购物车两份"
        assertTrue(mealProposal(PlanAnswer("mcd_cart"),two,two) is com.life0423.carddock.core.port.DiscoveryResult.Incomplete)
    }

    @Test fun approximateDemandKeepsOriginalCluesAndRequiresOfficialChoice() {
        val demand = "麦当劳当前门店，找名称相近的套餐，有南瓜和薯条"
        val answer = PlanAnswer("mcd_search", product = "模型猜的正式名", store = "当前门店",
            optionHints = listOf("南瓜", "薯条", "臆测配餐"))
        val plan = (mealProposal(answer, demand, demand) as com.life0423.carddock.core.port.DiscoveryResult.Plans).choices.single()
        val target = requireNotNull(plan.rule.meal)
        for (route in listOf(PlanAnswer("clarify", missing="object"), PlanAnswer("mcd_meal", product="套餐"))) {
            val fallback = mealProposal(route, demand, demand) as com.life0423.carddock.core.port.DiscoveryResult.Plans
            assertTrue(requireNotNull(fallback.choices.single().rule.meal).searchByDescription)
        }
        assertTrue(target.searchByDescription)
        assertEquals(demand, target.product)
        assertNull(target.store)
        assertEquals(listOf("南瓜", "薯条"), target.optionHints)
        assertNull(plan.rule.officialAction)
        assertTrue(mealProposal(answer.copy(store = "其他餐厅"), demand, demand) is com.life0423.carddock.core.port.DiscoveryResult.Incomplete)
    }

    @Test fun discoveryUsesOnlyOfferedToolAndCurrentLabel() {
        val offers = listOf(MenuCall("open_category", "午市"), MenuCall("scroll_products"))
        fun reply(tool: String, args: String) = kotlinx.serialization.json.buildJsonObject {
            put("tool_calls", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.buildJsonObject {
                put("function", kotlinx.serialization.json.buildJsonObject {
                    put("name", kotlinx.serialization.json.JsonPrimitive(tool))
                    put("arguments", kotlinx.serialization.json.JsonPrimitive(args))
                })
            })))
        }
        assertEquals(offers.first(), decodeMenuCall(reply("open_category", """{"label":"午市"}"""), offers))
        assertEquals(offers.last(), decodeMenuCall(reply("scroll_products", "{}"), offers))
        assertNull(decodeMenuCall(reply("open_category", """{"label":"购物车"}"""), offers))
        assertNull(decodeMenuCall(reply("scroll_products", """{"display":0}"""), offers))
        assertNull(decodeMenuCall(reply("checkout", "{}"), offers))
        assertNull(decodeMenuCall(kotlinx.serialization.json.JsonObject(emptyMap()), offers))
    }

    @Test fun discoveryKeepsNewDishAndOnlyReadsOptionsFromItsGroup() {
        val target = MealTarget("当场选的套餐", "测试餐厅")
        val plan = mealPlans("打开麦当劳选择「当场选的套餐」", target).choices.single()
        assertEquals(EXPLORE_MEAL, plan.rule.targetKey)
        assertEquals(target, plan.rule.meal)
        assertNull(plan.rule.officialAction)
        val section = page(row(-1,"rv_product",""), row(0,"section",""), row(1,"tv_product_group_name","主餐"),
            row(1,"rl_select_bg",""), row(3,"tv_product_name","现场选项"), row(0,"section",""),
            row(5,"tv_product_group_name","饮品规格"), row(5,"rv_standards_type","小杯"), row(-1,"rv_standards_type","无关规格"))
        assertEquals(mapOf("主餐" to listOf("现场选项"), "饮品规格" to listOf("小杯")), section.choicesOnPage(includeSpecs = true))
    }

    @Test fun scrollingIntoEmptyTreeOrMenuCannotFinishDetail() {
        assertFalse(page().isDetailFrame("商品"))
        assertFalse(page(row(-1,"tv_buy_type","到店取餐"), row(-1,"tv_address","测试餐厅")).isDetailFrame("商品"))
        assertTrue(page(row(-1,"sl_view",""), row(-1,"tv_add_cart","加入购物车")).isDetailFrame("商品"))
        val section = page(row(-1,"rv_standards",""), row(0,"section",""), row(1,"tv_product_group_name","杯型"),
            row(1,"rv_standards_type","大杯").copy(enabled=false), row(1,"rv_standards_type","小杯"))
        val stopped = mutableListOf<String>()
        assertEquals(mapOf("杯型" to listOf("小杯")), section.choicesOnPage(true) { _, name, why -> stopped += name + why })
        assertEquals(listOf("大杯当前不可选"), stopped)
    }

    @Test fun reportsOnlyThisProductsAvailability() {
        for (reason in listOf("已下架", "售罄", "非售卖时段")) {
            val menu = page(row(-1,"rl_item",""), row(0,"tv_name",CONE), row(0,"status",reason),
                row(-1,"rl_item",""), row(3,"tv_name",MEAL_TITLE))
            assertEquals(reason, menu.unavailable(CONE))
            assertNull(menu.unavailable(MEAL_TITLE))
            assertNull(menu.selected(CONE,"测试门店"))
        }
    }

    @Test fun detailNeedsNamedProductAndPurchaseControl() {
        val detail = page(row(-1,"tv_product_name",CONE), row(-1,"tv_product_group_name","规格"),
            row(-1,"rv_standards_type","单品"), row(-1,"option_status","已售罄"),
            row(-1,"tv_add_cart","¥5 加入购物车"))
        assertNull(detail.unavailable(CONE))
        assertNull(detail.selected(MEAL_TITLE,"测试门店"))
        assertEquals("单品", detail.selected(CONE,"测试门店")!!.snapshot.fields["options"])
        assertEquals("已下架", page(row(-1,"tv_product_name",CONE), row(-1,"tv_add_cart","已下架")).unavailable(CONE))
    }

    @Test fun mealOptionsBelongToTheirMenuGroup() {
        val menu = page(row(-1, "ll_product_title", ""), row(0, "tv_product_name", MEAL_TITLE),
            row(-1, "rv_product", ""), row(2, "section", ""), row(3, "tv_product_group_name", "分组一"),
            row(3, "rl_select_bg", ""), row(5, "tv_product_name", "选项甲"),
            row(3, "rl_select_bg", ""), row(7, "tv_product_name", "售罄项"), row(7, "status", "已售罄"),
            row(2, "section", ""), row(10, "tv_product_group_name", "分组二"),
            row(10, "rl_select_bg", ""), row(12, "tv_product_name", "选项乙"),
            row(-1, "tv_product_label_name", "已选摘要"), row(-1, "tv_add_cart", "加入购物车"))
        assertEquals(MEAL_TITLE, menu.product)
        assertTrue(menu.detailOffersProduct)
        assertEquals(mapOf("分组一" to listOf("选项甲"), "分组二" to listOf("选项乙")), menu.choicesOnPage())
        assertFalse(page(row(-1, "tv_add_cart", "暂不可用")).detailOffersProduct)
    }

    @Test fun unavailablePairExplainsWhichChoiceAndKeepsAlternatives() {
        val group = "白区（单选）"
        val detail = page(row(-1, "rv_product", ""), row(0, "section", ""), row(1, "tv_product_group_name", group),
            row(1, "rl_select_bg", ""), row(3, "tv_product_name", "香芋派"), row(3, "status", "已售罄"),
            row(1, "rl_select_bg", ""), row(6, "tv_product_name", "玉米杯"), row(-1, "tv_add_cart", "加入购物车").copy(enabled = false))
        val unavailable = mutableMapOf<String, String>()
        val options = detail.choicesOnPage { key, name, reason -> unavailable[key] = "「$name」$reason" }
        assertFalse(detail.detailOffersProduct)
        val target = MealTarget(MEAL_TITLE, "测试门店", mapOf(group to "香芋派"))
        val issue = fixedMealResult("测试门店", target, options, unavailable) as com.life0423.carddock.core.port.DeviceResponse.NeedsUser
        assertTrue(issue.message.contains("「香芋派」已售罄"))
        assertEquals(listOf("玉米杯"), issue.mealMenu?.groups?.get(group))
    }

    @Test fun clippedAdjacentGroupCannotLendItsOptions() {
        val menu = page(row(-1, "rv_product", ""), row(0, "section", ""), row(1, "tv_product_group_name", "红区（单选）"),
            row(0, "section", ""), row(3, "rl_select_bg", ""), row(4, "tv_product_name", "香芋派"))
        assertTrue(menu.choicesOnPage().isEmpty())
    }

    @Test fun fixedPairNeedsExplicitChoiceAndFreshAgreement() {
        val groups = mapOf("分组一" to listOf("甲","乙"), "分组二" to listOf("丙"))
        val first = fixedMealResult("门店A", MealTarget(MEAL_TITLE), groups) as com.life0423.carddock.core.port.DeviceResponse.NeedsUser
        assertEquals(groups, first.mealMenu?.groups)
        val target = MealTarget(MEAL_TITLE, "门店A", mapOf("分组一" to "乙", "分组二" to "丙"))
        val ready = fixedMealResult("门店A", target, groups) as com.life0423.carddock.core.port.DeviceResponse.Ready
        assertEquals("乙 + 丙", ready.snapshot.fields["pair"])
        val missing = fixedMealResult("门店A", target, groups - "分组二") as com.life0423.carddock.core.port.DeviceResponse.NeedsUser
        assertTrue(missing.message.contains("分组二 未见「丙」"))
        assertTrue(fixedMealResult("门店B", target, groups) is com.life0423.carddock.core.port.DeviceResponse.NeedsUser)
        assertTrue(fixedMealResult("门店A", target, groups + ("分组一" to listOf("甲"))) is com.life0423.carddock.core.port.DeviceResponse.NeedsUser)
    }

    @Test fun literalDemandPreservesStoreAndNeverChangesDish() {
        assertEquals(MealTarget(CONE,"测试餐厅"), explicitMeal("打开麦当劳（测试餐厅）选择「灰焰圆筒」"))
        assertEquals(MealTarget(MEAL_TITLE), explicitMeal("打开麦当劳选择随心选套餐加入购物车"))
        assertEquals(MealTarget("指定单品"), explicitMeal("打开麦当劳选择「指定单品」"))
        assertNull(explicitMeal("打开麦当劳选择「灰焰圆筒」并付款"))
        val choices = mealPlans("打开麦当劳选择「灰焰圆筒」",MealTarget(CONE)).choices
        assertEquals(CONE, choices.first().rule.targetKey)
        assertNull(choices.last().rule.meal)
    }
}
