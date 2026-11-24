package com.life0423.carddock.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.widget.Toast
import com.life0423.carddock.ai.CONE
import com.life0423.carddock.ai.MCD_PACKAGE
import com.life0423.carddock.core.port.DeviceResponse
import com.life0423.carddock.core.port.DeviceSnapshot
import com.life0423.carddock.core.rule.MealTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val periodEnding = Regex("(?:早餐|午餐|下午茶|晚餐|夜市)距结束还剩[0-9]{1,3}分钟")
private val stopped = setOf("已下架", "下架", "已售罄", "售罄", "已售完", "售完", "暂时售罄", "已停售", "非售卖时段", "不在供应时段", "已过供应时段")

internal val mealStorePending = DeviceResponse.NeedsUser("已进入即时菜单，门店名称仍未显示。请在官方 App 确认定位或选择门店后重试。")
internal val mealHomeStalled = DeviceResponse.NeedsUser("麦当劳首页仍停在到店取餐入口，本次未进入即时菜单。可主动打开麦当劳后重试。")

/** 一次主动查看同店即时菜单及商品规格。仅返回屏上公开字段，忽略控件附带的数据。 */
internal class MealPage(window: DetailWindow) {
    private val rows = window.nodes
    private fun matches(field: String) = rows.indices.filter {
        rows[it].visible && rows[it].id == "$MCD_PACKAGE:id/$field" && rows[it].label.isNotBlank()
    }
    private val addresses = matches("tv_address").map { rows[it].label }.distinct()
    val needsStoreChoice = addresses.any { it in setOf("选择餐厅", "请选择门店") }
    val store = addresses.singleOrNull()?.takeUnless { needsStoreChoice || it.startsWith("定位中") || it == "正在定位" }
    val mode = matches("tv_buy_type").singleOrNull()?.let { rows[it].label }
    // 套餐组件和套餐本身都叫 tv_product_name，只有标题栏才代表正在核对的商品。
    val product = matches("tv_product_name").singleOrNull { rows.getOrNull(rows[it].parent)?.id == "$MCD_PACKAGE:id/ll_product_title" }?.let { rows[it].label }
        ?: matches("tv_product_name").singleOrNull { rows.getOrNull(rows[it].parent)?.id != "$MCD_PACKAGE:id/rl_select_bg" }?.let { rows[it].label }
    // 官方餐段提示只说明菜单即将变化，不等同于某个商品停售。
    val periodNotice = rows.firstOrNull { it.visible && periodEnding.matches(it.label) }?.label
    // 餐段倒计时是官方信息弹窗；只允许关闭此文案对应的“确定”。
    val periodDismiss = matches("tv_message").singleOrNull()?.let { index ->
        val message = rows[index]
        if (!periodEnding.matches(message.label) || rows.getOrNull(message.parent)?.id != "$MCD_PACKAGE:id/rl_dialog_bg") null
        else rows.visibleSubtree(message.parent).map { it.value }.singleOrNull {
            it.enabled && it.id == "$MCD_PACKAGE:id/tv_confirm" && it.label == "确定"
        }
    }
    val closedSession = rows.firstOrNull { it.visible && it.label == "夜市已结束，无法继续点餐" }?.label
    // 只从左栏分类取证，商品推广语中的“早餐”不能证明本次菜单的类型。
    val categories = matches("tv_name").filter { rows.getOrNull(rows[it].parent)?.id == "$MCD_PACKAGE:id/rl_menu_item" }
        .map { rows[it].label }
    val products = matches("tv_name").filter { rows.getOrNull(rows[it].parent)?.id == "$MCD_PACKAGE:id/rl_item" }
        .map { rows[it].label }.distinct()

    fun categoryFor(name: String): String? = categories.singleOrNull { label ->
        val title = label.replace("\n", "")
        when (name) {
            CONE -> title == "小食甜品/其他"
            com.life0423.carddock.ai.MEAL_TITLE -> "随心配" in title || "1+1" in title
            else -> false
        }
    }

    // 顶部配餐标注随实际选项更新。去除官方布局换行，用完整名称核对已选商品。
    val selectedItems = matches("tv_product_label_name").map { rows[it].label.replace("\n", "") }
    val quantity = matches("tv_count").singleOrNull()?.let { rows[it].label.toIntOrNull() }
    val cartPrice = matches("tv_add_cart").singleOrNull()?.takeIf { rows[it].enabled }?.let {
        Regex("""[¥￥]\s*([0-9]+(?:\.[0-9]{1,2})?)\s*加入购物车""").matchEntire(rows[it].label.replace('\u00a0', ' '))?.groupValues?.get(1)
    }
    val detailOffersProduct get() = matches("tv_add_cart").any { rows[it].enabled && "加入购物车" in rows[it].label }
    /** 标题滚出后，仍须看见详情独有的滚动容器与底栏，空树或菜单不能充当末页。 */
    fun isDetailFrame(name: String) = (product == null || product == name) && mode == null &&
        rows.any { it.visible && it.id == "$MCD_PACKAGE:id/sl_view" } && matches("tv_add_cart").isNotEmpty()

    val detailCanAdvance get() = rows.any { it.visible && it.id == "$MCD_PACKAGE:id/sl_view" && it.canGoForward }

    /** 标题与选项须同属一个分组行。探索同时读取官方独立的规格列表。 */
    fun choicesOnPage(includeSpecs: Boolean = false, onUnavailable: (String, String, String) -> Unit = { _, _, _ -> }): Map<String, List<String>> {
        fun section(index: Int) = generateSequence(index) { rows[it].parent.takeIf { p -> p >= 0 } }
            .firstOrNull {
                val list = rows.getOrNull(rows[it].parent)?.id
                list == "$MCD_PACKAGE:id/rv_product" || includeSpecs && list == "$MCD_PACKAGE:id/rv_standards"
            }
        val options = matches("tv_product_name").filter {
            val row = rows.getOrNull(rows[it].parent)
            row?.id == "$MCD_PACKAGE:id/rl_select_bg"
        }
        return buildMap {
            for (heading in matches("tv_product_group_name")) {
                val owner = section(heading) ?: continue
                val names = options.filter { section(it) == owner }.filter { index ->
                    val optionRoot = rows[index].parent
                    val status = rows.indices.firstOrNull { child -> rows[child].visible && rows[child].label in stopped &&
                        generateSequence(child) { rows[it].parent.takeIf { p -> p >= 0 } }.any { it == optionRoot } }
                    val reason = status?.let { rows[it].label } ?: "当前不可选".takeIf { !rows[optionRoot].enabled || !rows[index].enabled }
                    if (reason != null) onUnavailable(rows[heading].label, rows[index].label, reason)
                    reason == null
                }.map { rows[it].label }.distinct()
                val specs = if (includeSpecs) matches("rv_standards_type").filter { section(it) == owner }.mapNotNull {
                    if (rows[it].enabled) rows[it].label else {
                        onUnavailable(rows[heading].label, rows[it].label, "当前不可选")
                        null
                    }
                } else emptyList()
                if (names.isNotEmpty() || specs.isNotEmpty()) put(rows[heading].label, (specs + names).distinct())
            }
        }
    }

    fun hasProduct(name: String) = matches("tv_name").any { rows[it].label == name && rows.getOrNull(rows[it].parent)?.id == "$MCD_PACKAGE:id/rl_item" }

    /** 售罄只归给本商品行或已打开的本商品规格，不借用相邻商品或促销的提示。 */
    fun unavailable(name: String): String? {
        val item = matches("tv_name").singleOrNull { rows[it].label == name }?.let { rows[it].parent }
        val itemRow = item?.takeIf { rows.getOrNull(it)?.id == "$MCD_PACKAGE:id/rl_item" }
        if (product == name) return matches("tv_add_cart").map { rows[it].label }.firstOrNull { it in stopped }
        val labels = rows.indices.filter { index ->
            val row = rows[index]
            row.visible && row.label.isNotBlank() && itemRow != null &&
                generateSequence(index) { rows[it].parent.takeIf { p -> p >= 0 } }.any { it == itemRow }
        }.map { rows[it].label }
        return labels.firstOrNull { it in stopped }
    }

    fun selected(name: String, menuStore: String): DeviceResponse.Ready? {
        if (product != name || matches("tv_add_cart").none { "加入购物车" in rows[it].label }) return null
        val options = matches("rv_standards_type").map { rows[it].label }.distinct()
        if (options.isEmpty() || matches("tv_product_group_name").isEmpty()) return null
        return DeviceResponse.Ready(DeviceSnapshot(MCD_PACKAGE, product,
            mapOf("entry" to product, "store" to menuStore, "options" to options.joinToString("、")), "麦当劳官方即时菜单"))
    }
}

/** 只导航到规格页。选择门店、处理首页活动和加购均交给用户，普通刷新不会发起导航。 */
internal suspend fun openMealPage(context: Context, target: MealTarget): DeviceResponse = withContext(Dispatchers.Main.immediate) {
    val service = CardDockAccessibilityService.instance
        ?: return@withContext DeviceResponse.NeedsUser("请在「手机运行环境」开启卡坞页面读取，再核验商品。", com.life0423.carddock.core.model.PhoneCapability.PAGE_READER)
    val launch = context.packageManager.getLaunchIntentForPackage(MCD_PACKAGE)
        ?: return@withContext DeviceResponse.NeedsUser("请先安装麦当劳官方 App。")
    try { context.startActivity(launch) } catch (_: ActivityNotFoundException) {
        return@withContext DeviceResponse.NeedsUser("麦当劳入口已变化，请重新试运行。")
    }
    Toast.makeText(context, "请打开到店取餐即时菜单，核验后返回卡坞。", Toast.LENGTH_LONG).show()
    readMealMenu(context, service, target, null) { true }
}

/** 前台仅用于用户主动直达。后台检查每步都绑定独立显示屏，不回退到主屏。 */
internal suspend fun readMealMenu(context: Context, service: CardDockAccessibilityService, target: MealTarget,
    screen: Int?, fixed: Boolean = false, startup: (String) -> Unit = {}, connected: () -> Boolean): DeviceResponse {
    var homeOffers = 0
    var homeAttempts = 0
    var lastHomeTap = -4
    var lastEntry: android.graphics.Rect? = null
    var sawEntry = false
    var sawMenuHeader = false
    var needsStoreChoice = false
    var breakfastCategory: String? = null
    var periodNotice: String? = null
    var menuStore: String? = null
    var sawProduct = false
    var openedCategory = false
    var categoryScrolls = 0
    var openedItem = false
    var remindedMenu = false
    var entered = false
    var menuReads = 0
    val choices = linkedMapOf<String, MutableSet<String>>()
    var detailScrolls = 0
    var sawFixedDetail = false
    val unavailableChoices = linkedMapOf<String, String>()
    repeat(if (screen == null) 60 else 40) { tick ->
        delay(450)
        if (!connected()) return DeviceResponse.NeedsUser("本次后台查询已中断，请检查运行环境后重试。")
        val window = service.observeDetail(MCD_PACKAGE, screen, geometry = screen != null)
        if (window?.source != MCD_PACKAGE) {
            if (entered && window?.source != null)
                return DeviceResponse.NeedsUser("已离开麦当劳，本次尚未核验到「${target.product}」规格页。")
            return@repeat
        }
        entered = true
        val page = MealPage(window)
        page.periodNotice?.let { periodNotice = it }
        page.closedSession?.let { return DeviceResponse.NeedsUser("当前官方点餐入口提示「$it」。请回到即时菜单再试。") }
        // 首页首次布局会移动入口。手势入队不等于官方已跳转，只在同一入口位置稳定且仍在首页时补试。
        if (screen != null && menuStore == null && page.mode == null && page.product == null) {
            val entry = window.nodes.singleOrNull { it.visible && it.id == "$MCD_PACKAGE:id/tv_title2" && it.label == "到店取餐" }
            if (entry != null) sawEntry = true
            if (entry?.bounds != null && entry.bounds == lastEntry && homeAttempts < 3 && tick - lastHomeTap >= 4) {
                homeOffers++
                if (service.tapReadTarget(screen, MCD_PACKAGE, entry, entry.bounds, keepGestureOnLabel = true)) {
                    homeAttempts++
                    lastHomeTap = tick
                    startup("entry offered=$homeOffers dispatched=$homeAttempts")
                }
            }
            lastEntry = entry?.bounds
        }
        if (page.mode != null && menuStore == null) {
            sawMenuHeader = true
            needsStoreChoice = page.needsStoreChoice
        }
        val store = page.store
        if (store != null && page.mode != null) {
            if (page.mode != "到店取餐" || (target.store != null && target.store != store) || (menuStore != null && menuStore != store))
                return DeviceResponse.NeedsUser("当前为「$store · ${page.mode}」。请确认并手动切到${target.store ?: menuStore ?: "所选门店"}的即时到店取餐菜单，再试一次。")
            if (menuStore == null) startup("menu offered=$homeOffers dispatched=$homeAttempts")
            menuStore = store
            menuReads++
            if ("早餐堡" in page.categories) breakfastCategory = "早餐堡"
        }
        if (screen == null && menuStore == null && page.product != null && !remindedMenu) {
            Toast.makeText(context, "请先返回门店菜单，本次将继续核验门店和商品。", Toast.LENGTH_LONG).show()
            remindedMenu = true
        }
        val current = menuStore ?: return@repeat
        page.unavailable(target.product)?.let {
            return DeviceResponse.NeedsUser("$current · ${target.product}：官方显示「$it」。可稍后重试或更改商品。")
        }
        if (fixed && page.product == target.product) {
            sawFixedDetail = true
            if (target.product != com.life0423.carddock.ai.MEAL_TITLE)
                return DeviceResponse.NeedsUser("$current · 已核对「${target.product}」，此商品的固定搭配规则尚待核验。")
            // 同一详情还有“规格”和升级项；常用 1+1 只核对官方红区、白区各一项。
            val visibleChoices = page.choicesOnPage { group, option, reason ->
                if (target.choices[group] == option) unavailableChoices[group] = "「$option」$reason"
            }
            for ((group, options) in visibleChoices) {
                if (group in setOf("红区（单选）", "白区（单选）")) choices.getOrPut(group) { linkedSetOf() }.addAll(options)
            }
            val bothGroups = choices.size == 2 && choices.values.all { it.isNotEmpty() }
            startup("menu verified; groups=${choices.mapValues { it.value.size }} scrolls=$detailScrolls forward=${page.detailCanAdvance}")
            val pickedVisible = target.choices.size == 2 && target.choices.all { (group, option) -> option in choices[group].orEmpty() }
            val canReport = bothGroups && (page.detailOffersProduct || unavailableChoices.isNotEmpty())
            if (canReport && (pickedVisible || unavailableChoices.isNotEmpty() || !page.detailCanAdvance || detailScrolls >= 12))
                return fixedMealResult(current, target, choices.mapValues { it.value.toList() }, unavailableChoices)
            val scrollBox = window.nodes.singleOrNull { it.visible && it.id == "$MCD_PACKAGE:id/sl_view" }?.bounds
            if (page.detailCanAdvance && detailScrolls < 12 && screen != null && scrollBox != null) {
                // 顶部半屏先小幅滚动，避免跳过红区标题；红区取到后再翻入白区。
                val red = choices["红区（单选）"].orEmpty()
                val requestedRed = target.choices["红区（单选）"]
                val moved = if (red.isEmpty() || requestedRed != null && requestedRed !in red && detailScrolls < 4)
                    service.advanceCollection(screen, MCD_PACKAGE, scrollBox)
                else service.scrollMealList(screen, detail = true)
                if (moved) { detailScrolls++; return@repeat }
            }
            if (canReport) return fixedMealResult(current, target, choices.mapValues { it.value.toList() }, unavailableChoices)
            return@repeat
        }
        if (!fixed) page.selected(target.product, current)?.let { return it }
        if (store == null || page.mode != "到店取餐") return@repeat
        if (!openedItem && page.hasProduct(target.product)) {
            openedItem = service.clickMealMenuLabel(target.product, category = false, screen = screen)
            sawProduct = true
        } else if (target.product in setOf(CONE, com.life0423.carddock.ai.MEAL_TITLE) && !sawProduct && !openedCategory) {
            val category = page.categoryFor(target.product)
            if (category != null) openedCategory = service.clickMealMenuLabel(category, category = true, screen = screen)
            else if (categoryScrolls < 3 && menuReads % 2 == 0) {
                if (service.scrollMealList(screen)) categoryScrolls++ else categoryScrolls = 3
            }
        }
        if (!sawProduct && menuReads >= 12)
            return DeviceResponse.NeedsUser("$current · " +
                (periodNotice?.let { "官方餐段提示「$it」。" } ?: breakfastCategory?.let { "即时菜单显示「$it」分类。" }.orEmpty()) +
                "已查看的菜单区域未找到「${target.product}」。官方未标明该商品的供应时间，可稍后重试。",
                mealMenu = if (fixed && target.store == null) com.life0423.carddock.core.rule.MealMenu(current, emptyMap()) else null)
    }
    if (sawFixedDetail && menuStore != null && target.choices.isNotEmpty()) {
        val incomplete = target.choices.any { (group, option) -> option !in choices[group].orEmpty() }
        if (unavailableChoices.isNotEmpty() || incomplete)
            return fixedMealResult(menuStore, target, choices.mapValues { it.value.toList() }, unavailableChoices)
    }
    if (screen != null && menuStore == null && !sawMenuHeader && sawEntry) {
        startup("stalled offered=$homeOffers dispatched=$homeAttempts")
        return mealHomeStalled
    }
    if (screen != null && menuStore == null && sawMenuHeader) {
        startup(if (needsStoreChoice) "menu requires store selection" else "menu store pending")
        if (periodNotice != null) return DeviceResponse.NeedsUser("官方餐段提示「$periodNotice」，本次尚未核验到门店。请在即时菜单更新后重试。")
        return if (needsStoreChoice) DeviceResponse.NeedsUser("官方即时菜单正在等待选择门店。请在麦当劳选择${target.store ?: "到店取餐门店"}后重试。")
            else mealStorePending
    }
    return DeviceResponse.NeedsUser(if (screen != null && menuStore == null)
            "未在麦当劳后台页面读到到店取餐入口。可先打开官方 App 确认首页状态，再试一次。"
        else if (menuStore == null) "未核验到即时门店。请打开麦当劳到店取餐菜单；如已在规格页，请先返回菜单再试。"
        else if (sawFixedDetail) "$menuStore · 已进入「${target.product}」，尚未核验完整的两个可选分组和供应状态，可稍后重试。"
        else if (sawProduct) "已看到「${target.product}」，尚未核验到规格。可重试，在核验期间从当前门店菜单手动点开商品。"
        else "尚未核验到「${target.product}」的可选规格。请查看官方门店菜单后重试。")
}


/** 单独重读两组之后才签发固定搭配快照。初次选项只用于卡坞本地选择。 */
internal fun fixedMealResult(store: String, target: MealTarget, groups: Map<String, List<String>>,
    unavailableChoices: Map<String, String> = emptyMap()): DeviceResponse {
    val menu = com.life0423.carddock.core.rule.MealMenu(store, groups)
    if (target.choices.isEmpty()) return DeviceResponse.NeedsUser("请选择要保存在卡坞的搭配。", mealMenu = menu)
    if (target.store != store) return DeviceResponse.NeedsUser("当前门店为「$store」，请切回「${target.store}」后重试。", mealMenu = menu)
    if (unavailableChoices.isNotEmpty()) return DeviceResponse.NeedsUser("$store · " +
        unavailableChoices.entries.joinToString("、") { (group, status) -> "$group $status" } + "。可稍后再试或更改搭配。", mealMenu = menu)
    val missing = target.choices.filter { (group, option) -> option !in groups[group].orEmpty() }
    if (missing.isNotEmpty()) return DeviceResponse.NeedsUser("$store · 本次可见选项中，" +
        missing.entries.joinToString("、") { (group, option) -> "$group 未见「$option」" } + "。请稍后重试或更改搭配。", mealMenu = menu)
    if (target.choices.keys != groups.keys) return DeviceResponse.NeedsUser("$store · 请重新核对固定搭配的分组。", mealMenu = menu)
    return DeviceResponse.Ready(DeviceSnapshot(MCD_PACKAGE, com.life0423.carddock.core.rule.OfficialAction.REPEAT_MEAL.name,
        mapOf("entry" to target.product, "store" to store, "pair" to groups.keys.joinToString(" + ") { target.choices.getValue(it) },
            "options" to groups.entries.joinToString("\n") { (group, options) -> "$group：${options.joinToString("、")}" }), "麦当劳官方即时菜单"))
}
