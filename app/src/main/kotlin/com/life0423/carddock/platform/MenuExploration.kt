package com.life0423.carddock.platform

import com.life0423.carddock.ai.*
import com.life0423.carddock.core.port.DeviceResponse
import com.life0423.carddock.core.port.DeviceSnapshot
import com.life0423.carddock.core.rule.MealTarget
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException

/** 每次观察只公布首页入口、分类、同名商品和只读列表。模型无法指定包、坐标或任意控件。 */
private class MenuObservation(val window: DetailWindow) {
    val page = MealPage(window)
    val pickup = window.nodes.singleOrNull { it.visible && it.id == "$MCD_PACKAGE:id/tv_title2" && it.label == "到店取餐" }
    val productList = window.nodes.indexOfFirst { it.visible && it.id == "$MCD_PACKAGE:id/rl_item" }.takeIf { it >= 0 }?.let { row ->
        generateSequence(row) { window.nodes[it].parent.takeIf { parent -> parent >= 0 } }
            .map { window.nodes[it] }.firstOrNull { it.scrollable }
    }
    val categoriesCanScroll = window.nodes.any { it.visible && it.id == "$MCD_PACKAGE:id/rv_menu" && it.canGoForward }

    fun offers(target: MealTarget, visited: Set<String>, opened: Boolean): List<MenuCall> = buildList {
        if (!opened && page.periodDismiss != null) add(MenuCall("dismiss_period_notice"))
        else if (opened && page.product == target.product) add(MenuCall("read_groups"))
        else if (!opened && page.mode == "到店取餐" && page.store != null && page.products.isNotEmpty()) {
            val exact = !target.searchByDescription && target.product in page.products
            if (exact) add(MenuCall("open_product", target.product))
            page.categories.distinct().filterNot(visited::contains).forEach { add(MenuCall("open_category", it)) }
            if (categoriesCanScroll) add(MenuCall("scroll_categories"))
            if (productList?.canGoForward == true) add(MenuCall("scroll_products"))
            if (!exact) {
                page.products.forEach { add(MenuCall("ask_product", it)) }
                add(MenuCall("report_missing"))
            }
        } else if (!opened && pickup != null && page.mode == null) add(MenuCall("open_pickup_menu"))
    }
}

/** 在已分配的独立屏上交替观察与执行；网络往返后重读页面，只执行仍在当前菜单上的工具。 */
internal suspend fun exploreMenu(reader: CardDockAccessibilityService, screen: Int, target: MealTarget,
    config: ModelConfig, cart: Boolean, prepareCart: Boolean, connected: () -> Boolean, trace: (String) -> Unit): DeviceResponse {
    val visited = linkedSetOf<String>()
    val history = mutableListOf<String>()
    var store: String? = null
    var opened = false
    var cartBefore = 0
    var matchingBefore = 0
    fun observe() = reader.observeDetail(MCD_PACKAGE, screen, geometry = true)
        ?.takeIf { it.source == MCD_PACKAGE && !it.password }?.let(::MenuObservation)
    fun mismatch(page: MealPage) = page.mode != null && (page.mode != "到店取餐" ||
        page.store != null && (target.store?.let { it != page.store } == true || store?.let { it != page.store } == true))
    val interrupted = DeviceResponse.NeedsUser("后台试查已中断。请确认读屏连接，并在主屏离开麦当劳后重试。")
    repeat(18) { turn ->
        var observation: MenuObservation? = null
        val settled = withTimeoutOrNull(if (history.lastOrNull()?.startsWith("open_pickup_menu") == true) 20_000L else 8_000L) {
            while (connected()) {
                delay(350)
                val frame = observe() ?: continue
                observation = frame
                if (mismatch(frame.page) || frame.page.needsStoreChoice || frame.page.closedSession != null ||
                    opened && frame.page.product != null || frame.offers(target, visited, opened).isNotEmpty()) break
            }
            true
        }
        if (!connected()) return interrupted
        val frame = observation
        if (settled == null || frame == null) {
            trace("timeout turn=$turn mode=${frame?.page?.mode} store=${frame?.page?.store != null} products=${frame?.page?.products?.size}")
            val message = frame?.window?.nodes?.singleOrNull { it.visible && it.id == "$MCD_PACKAGE:id/tv_message" }?.label
            if (!message.isNullOrBlank()) return DeviceResponse.NeedsUser("麦当劳提示「$message」，请在官方页核对后重试。")
            if (frame?.page?.mode == "到店取餐" && frame.page.store == null) return mealStorePending
            return DeviceResponse.NeedsUser("等待麦当劳后台页面超时，未核对到目标。请确认官方门店菜单可用后重试。")
        }
        val page = frame.page
        if (mismatch(page) || page.needsStoreChoice) return DeviceResponse.NeedsUser("请在麦当劳切到${target.store ?: store ?: "所需门店"}的即时到店取餐菜单后重试。")
        page.closedSession?.let { return DeviceResponse.NeedsUser("官方菜单提示「$it」。") }
        if (opened && page.product != target.product) return DeviceResponse.NeedsUser("详情页与所选商品不一致，请重新核对。")
        if (page.mode == "到店取餐" && page.store != null) store = page.store
        // 从能力入口直接开始时没有指定商品，让用户在刚读到的同店菜单中选。
        if (cart && target.searchByDescription && target.product == CART_MENU_PROMPT &&
            page.mode == "到店取餐" && page.store != null && page.products.isNotEmpty())
            return DeviceResponse.NeedsUser("请选择要核对搭配的官方商品。",
                mealMenu = com.life0423.carddock.core.rule.MealMenu(page.store, emptyMap(), page.products))
        page.unavailable(target.product)?.let { return DeviceResponse.NeedsUser("$store · ${target.product}，官方显示「$it」。") }
        if (frame.pickup != null && page.mode == null && history.count { it.startsWith("open_pickup_menu") } >= 3) return mealHomeStalled
        val offers = frame.offers(target, visited, opened)
        trace("observe turn=$turn stage=${if (opened) "detail" else if (page.mode != null) "menu" else "home"} items=${page.products.size} categories=${page.categories.size} offers=${offers.size}")
        val call = try {
            chooseMenuTool(config, target.product, offers, page.products, history)
        } catch (e: IOException) {
            trace("model error=${e.message?.takeIf { it == "invalid_tool_call" || it.matches(Regex("HTTP [0-9]{3}")) } ?: "connection"}")
            return DeviceResponse.NeedsUser(if (e.message == "invalid_tool_call") "模型没有返回当前菜单支持的工具调用。请检查模型的工具调用能力后重试。"
                else "模型请求失败，请核对模型配置或网络后重试。")
        } catch (_: SerializationException) {
            return DeviceResponse.NeedsUser("模型工具回复格式无效，请重试。")
        }
        currentCoroutineContext().ensureActive()
        trace("model tool=${call.tool}")
        if (!connected()) return interrupted
        val fresh = observe() ?: return interrupted
        if (mismatch(fresh.page) || fresh.page.store != page.store ||
            call !in fresh.offers(target, visited, opened)) return DeviceResponse.NeedsUser("官方页面在模型响应期间发生变化，请重新试查。")
        val dispatched = when (call.tool) {
            "dismiss_period_notice" -> fresh.page.periodDismiss!!.let { reader.tapReadTarget(screen, MCD_PACKAGE, it, it.bounds, keepGestureOnLabel = true) }
            "open_pickup_menu" -> fresh.pickup!!.let { reader.tapReadTarget(screen, MCD_PACKAGE, it, it.bounds, keepGestureOnLabel = true) }
            "open_category" -> reader.clickMealMenuLabel(call.label, category = true, screen = screen)
            "scroll_categories" -> reader.scrollMealList(screen)
            "scroll_products" -> fresh.productList?.bounds?.let { reader.advanceCollection(screen, MCD_PACKAGE, it) } == true
            "open_product" -> {
                if (prepareCart) {
                    cartBefore = menuCartCount(fresh.window) ?: return DeviceResponse.NeedsUser("未能核对该店当前购物车件数，请先打开官方购物车检查。")
                    matchingBefore = readPriorMealCount(reader, screen, target, cartBefore, connected)
                        ?: return DeviceResponse.NeedsUser("未能读全该店已有购物袋，请在官方页核对。")
                    trace("cart baseline total=$cartBefore matching=$matchingBefore")
                }
                reader.clickMealMenuLabel(call.label, category = false, screen = screen).also { opened = it }
            }
            "ask_product" -> return DeviceResponse.NeedsUser("模型找到相近商品「${call.label}」。请选择要核对的官方商品。",
                mealMenu = com.life0423.carddock.core.rule.MealMenu(requireNotNull(store), emptyMap(), listOf(call.label) + (fresh.page.products - call.label)))
            "report_missing" -> return DeviceResponse.NeedsUser("$store · 已查看的菜单区域未见「${target.product}」。")
            "read_groups" -> return if (prepareCart) {
                val verified = readMenuGroups(reader, screen, target, requireNotNull(store), fresh.window, true, connected, trace)
                if (verified !is DeviceResponse.Ready) return verified
                val prepared = prepareMealCart(reader, screen, target, connected)
                if (prepared is DeviceResponse.Ready) prepared.copy(snapshot = prepared.snapshot.copy(fields = prepared.snapshot.fields + mapOf("before" to cartBefore.toString(), "matchingBefore" to matchingBefore.toString()))) else prepared
            }
                else readMenuGroups(reader, screen, target, requireNotNull(store), fresh.window, cart, connected, trace)
            else -> error("未知菜单工具")
        }
        trace("dispatch accepted=$dispatched")
        if (!dispatched) return DeviceResponse.NeedsUser("未能在官方页面执行本次菜单操作，请重试。")
        history += call.tool + " " + call.label
        if (call.tool == "open_category") visited += call.label
        delay(if (call.tool == "open_pickup_menu") 1_400 else 650)
    }
    return DeviceResponse.NeedsUser("本轮已查看的菜单区域未核对到「${target.product}」，请补充官方商品全名后重试。")
}

/** 官方摘要不带组名，跨组同名选项不能用摘要证明其归属。 */
internal fun ambiguousMealChoices(target: MealTarget, options: Map<String, List<String>>) =
    target.choices.values.any { name -> options.values.count { name in it } > 1 }

/** read_groups 只滚动目标详情。分组按同一容器累积，售罄项带上官方状态一起显示。 */
private suspend fun readMenuGroups(reader: CardDockAccessibilityService, screen: Int, target: MealTarget, store: String,
    first: DetailWindow, cart: Boolean, connected: () -> Boolean, trace: (String) -> Unit): DeviceResponse {
    val product = target.product
    val groups = linkedMapOf<String, LinkedHashSet<String>>()
    val available = linkedMapOf<String, LinkedHashSet<String>>()
    val initialItems = MealPage(first).selectedItems
    var window = first
    repeat(16) { index ->
        val page = MealPage(window)
        if (!page.isDetailFrame(product) || page.store?.let { it != store } == true)
            return DeviceResponse.NeedsUser("详情页已变化，请重新试查。")
        page.unavailable(product)?.let { return DeviceResponse.NeedsUser("$store · $product，官方显示「$it」。") }
        val choices = page.choicesOnPage(includeSpecs = true) { group, option, status ->
            groups.getOrPut(group, ::linkedSetOf).add("$option（$status）")
        }
        for ((group, options) in choices) {
            groups.getOrPut(group, ::linkedSetOf).addAll(options)
            available.getOrPut(group, ::linkedSetOf).addAll(options)
        }
        trace("read detail frame=$index groups=${groups.size} options=${groups.values.sumOf { it.size }} forward=${page.detailCanAdvance}")
        if (!page.detailCanAdvance) {
            if (groups.isEmpty()) return DeviceResponse.NeedsUser("$store · 已进入「$product」，当前详情未读到可核对的分组和选项。")
            if (cart) {
                val options = available.filterKeys { "单选" in it }.mapValues { it.value.toList() }
                if (options.isEmpty()) return DeviceResponse.NeedsUser("该详情尚未读到可独立核对的单选配餐。")
                val defaults = options.mapNotNull { (group, names) -> names.singleOrNull { it in initialItems }?.let { group to it } }.toMap()
                if (ambiguousMealChoices(target, options))
                    return DeviceResponse.NeedsUser("多个配餐组有同名选项，无法从官方摘要分辨所属组。请在官方页核对。")
                if (target.store != store || target.choices.keys != options.keys || target.choices.any { (g,n) -> n !in options[g].orEmpty() })
                    return DeviceResponse.NeedsUser("请确认要加入购物车的搭配。", mealMenu = com.life0423.carddock.core.rule.MealMenu(store, options, defaults=defaults))
                return DeviceResponse.Ready(DeviceSnapshot(MCD_PACKAGE, com.life0423.carddock.core.rule.OfficialAction.ADD_MEAL.name,
                    mapOf("entry" to product, "store" to store, "pair" to target.choices.entries.joinToString("\n") { (g,n) -> "$g · $n" },
                        "options" to "官方当前规格 · 1 份"), "麦当劳官方即时菜单"))
            }
            val fields = mutableMapOf("entry" to product, "store" to store, "options" to groups.entries.joinToString("\n") { (group, items) ->
                "$group：${items.joinToString("、")}"
            })
            if (target.optionHints.isNotEmpty()) fields["matches"] = target.optionHints.joinToString("\n") { hint ->
                val hits = available.flatMap { (group, names) -> names.filter { hint in it }.map { "$group · $it" } }
                "$hint → " + hits.joinToString("、").ifEmpty { "本次可选项中未见同名或含该词的配餐" }
            }
            return DeviceResponse.Ready(DeviceSnapshot(MCD_PACKAGE, EXPLORE_MEAL, fields, "麦当劳官方即时菜单 · Mika 后台试查"))
        }
        val box = window.nodes.singleOrNull { it.visible && it.id == "$MCD_PACKAGE:id/sl_view" }?.bounds ?: return DeviceResponse.NeedsUser("分组列表位置已变化，请重新试查。")
        if (!connected() || !reader.advanceCollection(screen, MCD_PACKAGE, box)) return DeviceResponse.NeedsUser("商品分组读取中断，请重新试查。")
        delay(750)
        if (!connected()) return DeviceResponse.NeedsUser("后台页面连接已中断，请重试。")
        window = reader.observeDetail(MCD_PACKAGE, screen, geometry = true)?.takeIf { !it.password && it.source == MCD_PACKAGE }
            ?: return DeviceResponse.NeedsUser("未能重新读取官方详情，请重试。")
    }
    return DeviceResponse.NeedsUser("已进入「$product」，本次未读完商品分组，请重试。")
}
