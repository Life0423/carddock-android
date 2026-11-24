package com.life0423.carddock.platform

import com.life0423.carddock.ai.MCD_PACKAGE
import com.life0423.carddock.core.port.DeviceResponse
import com.life0423.carddock.core.port.DeviceSnapshot
import com.life0423.carddock.core.rule.MealTarget
import com.life0423.carddock.core.rule.OfficialAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** 官方当前规格的一份商品。确认时和交接到主屏后都比对完整配餐、数量与实时价。 */
internal fun MealPage.matchesCart(target: MealTarget, price: String) = target.choices.isNotEmpty() &&
    (store == null || store == target.store) && product == target.product && quantity == 1 && cartPrice == price &&
    selectedItems.sorted() == target.choices.values.sorted()

/** 购物车总件数取自官方菜单；系统未暴露数量时保留未知。 */
internal fun menuCartCount(window: DetailWindow): Int? {
    val nodes = window.nodes
    if (nodes.any { it.visible && it.id == "$MCD_PACKAGE:id/tv_total_price" && it.label == "未选购餐品" }) return 0
    // 每个菜单商品也有 tv_tip_number。总数只取购物袋角标。
    return nodes.singleOrNull { it.visible && it.id == "$MCD_PACKAGE:id/tv_tip_number" &&
        nodes.getOrNull(it.parent)?.id == "$MCD_PACKAGE:id/cl_tip_number" }?.label?.toIntOrNull()
}

/** 明细按购物袋行归属。仅在已读数量合计与袋上件数相符时返回相同搭配的份数。 */
internal fun cartSelectionCount(frame: DetailWindow, target: MealTarget, total: Int): Int? {
    val nodes = frame.nodes
    val list = nodes.indexOfFirst { it.visible && it.id == "$MCD_PACKAGE:id/rc_car_list" }
    if (list < 0) return null
    var counted = 0
    var matching = 0
    for ((index, row) in nodes.withIndex()) {
        if (!row.visible || row.parent != list || row.id != "$MCD_PACKAGE:id/root_view") continue
        val fields = nodes.visibleSubtree(index).map { it.value }
        fun label(id: String) = fields.singleOrNull { it.id == "$MCD_PACKAGE:id/$id" }?.label
        val quantity = label("tv_count")?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        counted += quantity
        if (label("tv_product_name") != target.product) continue
        val summary = label("tv_customize_info") ?: return null
        // 选项名本身可能含加号，例如“中杯怡泉+C”。逐个消耗完整配餐名。
        var remaining = "$summary+"
        val choices = target.choices.values.sortedByDescending { it.length }.toMutableList()
        while (choices.isNotEmpty()) {
            val name = choices.firstOrNull { remaining.startsWith("$it+") } ?: break
            remaining = remaining.removePrefix("$name+")
            choices.remove(name)
        }
        if (choices.isEmpty() && remaining.isEmpty()) matching += quantity
    }
    return matching.takeIf { counted == total }
}

/** 已有购物车时先读取本搭配的份数，再收起购物袋回到同店菜单。 */
internal suspend fun readPriorMealCount(reader: CardDockAccessibilityService, screen: Int, target: MealTarget,
    total: Int, connected: () -> Boolean): Int? {
    if (total == 0) return 0
    if (!reader.toggleMealBag(screen, target.store)) return null
    val count = withTimeoutOrNull(4_000) {
        var found: Int? = null
        while (connected() && found == null) {
            delay(200)
            val frame = reader.observeDetail(MCD_PACKAGE, screen) ?: continue
            found = cartSelectionCount(frame, target, total)
        }
        found
    } ?: return null
    if (!reader.toggleMealBag(screen, target.store)) return null
    return withTimeoutOrNull(4_000) {
        while (connected()) {
            delay(150)
            val frame = reader.observeDetail(MCD_PACKAGE, screen) ?: continue
            if (MealPage(frame).store == target.store && frame.nodes.none { it.visible && it.id == "$MCD_PACKAGE:id/rc_car_list" })
                return@withTimeoutOrNull count
        }
        null
    }
}

/** 只在具名配餐组内应用选择，再回顶部独立读取官方当前组合。这里不会加购。 */
internal suspend fun prepareMealCart(reader: CardDockAccessibilityService, screen: Int, target: MealTarget,
    connected: () -> Boolean): DeviceResponse {
    // 全菜单复核停在分组末页，先回到商品摘要，从官方实际选中状态开始应用本次配置。
    var window = withTimeoutOrNull(9000) {
        var current: DetailWindow? = null
        while (connected()) {
            current = reader.observeDetail(MCD_PACKAGE, screen, geometry=true)
            if (current == null || current.password) return@withTimeoutOrNull null
            if (!current.nodes.any { it.visible && it.id == "$MCD_PACKAGE:id/sl_view" && it.canGoBack }) break
            if (!reader.scrollMealList(screen, detail=true, backward=true)) return@withTimeoutOrNull null
            delay(400)
        }
        current
    } ?: return DeviceResponse.NeedsUser("未读到官方当前搭配，请重新试查。")
    val pending = target.choices.toMutableMap()
    val initial = MealPage(window)
    pending.entries.removeAll { it.value in initial.selectedItems }
    repeat(32) {
        if (!connected()) return DeviceResponse.NeedsUser("后台商品页已中断，请重试。")
        val page = MealPage(window)
        if (!page.isDetailFrame(target.product)) return DeviceResponse.NeedsUser("商品详情发生变化，请重新核对。")
        val price = page.cartPrice
        if (price != null && page.matchesCart(target, price)) return DeviceResponse.Ready(DeviceSnapshot(MCD_PACKAGE,
            OfficialAction.ADD_MEAL.name, mapOf("price" to price), "官方当前搭配"))
        val choices = page.choicesOnPage()
        val choice = pending.entries.firstOrNull { (group, name) -> name in choices[group].orEmpty() }
        if (choice != null) {
            val node = window.nodes.singleOrNull { it.visible && it.enabled && it.id == "$MCD_PACKAGE:id/tv_product_name" &&
                it.label == choice.value && window.nodes.getOrNull(it.parent)?.id == "$MCD_PACKAGE:id/rl_select_bg" }
            if (node?.bounds == null || !reader.tapReadTarget(screen, MCD_PACKAGE, node, node.bounds, keepGestureOnLabel = true))
                return DeviceResponse.NeedsUser("未能选中「${choice.value}」，请重新核对。")
            pending.remove(choice.key)
        } else {
            val list = window.nodes.singleOrNull { it.visible && it.id == "$MCD_PACKAGE:id/sl_view" }
            val moved = if (pending.isEmpty()) list?.canGoBack == true && reader.scrollMealList(screen, detail=true, backward=true)
                else list?.canGoForward == true && list.bounds?.let { reader.advanceCollection(screen, MCD_PACKAGE, it) } == true
            if (!moved) return DeviceResponse.NeedsUser("官方当前搭配或数量与模板不一致，请重新选择配餐。")
        }
        delay(650)
        window = reader.observeDetail(MCD_PACKAGE, screen, geometry=true)?.takeIf { !it.password }
            ?: return DeviceResponse.NeedsUser("官方商品页读取中断。")
    }
    return DeviceResponse.NeedsUser("未能核对全部配餐，请重新试查。")
}

/** 同一商品任务交接至主屏后，仅分派一次加购。之后只观察菜单及打开购物袋。 */
internal suspend fun addConfirmedMeal(reader: CardDockAccessibilityService, target: MealTarget, price: String,
    previous: Int, previousMatching: Int, trace: (String) -> Unit): DeviceResponse {
    fun observe() = reader.observeDetail(MCD_PACKAGE, 0)?.takeIf { it.source == MCD_PACKAGE && !it.password }
    val ready = withTimeoutOrNull(7_000) {
        while (observe()?.let(::MealPage)?.matchesCart(target, price) != true) delay(180)
        true
    }
    if (ready != true) return DeviceResponse.NeedsUser("主屏未确认同一商品、价格和搭配，本次未加购。")
    val accepted = reader.clickConfirmedMeal(target, price)
    trace("cart dispatch accepted=$accepted")
    if (!accepted) return DeviceResponse.NeedsUser("加购控件未确认受理，请先查看官方购物车。")
    var count: Int? = null
    var bagOpen = false
    val returned = withTimeoutOrNull(9_000) {
        while (true) {
            delay(250)
            val frame = observe() ?: continue
            if (MealPage(frame).let { it.store == target.store && it.mode == "到店取餐" }) {
                count = menuCartCount(frame)
                if (count != null && count != previous) {
                    bagOpen = frame.nodes.any { it.visible && it.id == "$MCD_PACKAGE:id/rc_car_list" }
                    break
                }
            }
        }
        true
    }
    trace("cart menu=${returned == true} before=$previous after=$count")
    if (returned != true || !bagOpen && !reader.toggleMealBag(0, target.store))
        return DeviceResponse.NeedsUser("已发出一次加购，请查看官方购物车核对结果。")
    val found = withTimeoutOrNull(4_000) {
        while (true) {
            delay(200)
            val frame = observe() ?: continue
            // 同名菜单行仍可能在购物袋下方，不作为回执。
            if (count == previous + 1 && menuCartCount(frame) == count &&
                cartSelectionCount(frame, target, previous + 1) == previousMatching + 1) break
        }
        true
    }
    trace("cart item=${found == true} previousMatching=$previousMatching")
    if (found != true) return DeviceResponse.NeedsUser("已打开官方购物车，请核对商品、搭配和数量。")
    return DeviceResponse.Ready(DeviceSnapshot(MCD_PACKAGE, OfficialAction.ADD_MEAL.name, mapOf("entry" to target.product), "官方购物车已核对"))
}

