package com.life0423.carddock

import com.life0423.carddock.ai.requestModel

import android.content.Context
import com.life0423.carddock.ai.MockRuleCompiler
import com.life0423.carddock.ai.PersonalRuleCompiler
import com.life0423.carddock.data.SquareStore
import com.life0423.carddock.ai.CainiaoModelCompiler
import com.life0423.carddock.ai.ModelSettings
import com.life0423.carddock.ai.ModelSummary
import com.life0423.carddock.core.port.ReadOptions
import com.life0423.carddock.data.ShoppingItem
import com.life0423.carddock.data.ShoppingStore
import com.life0423.carddock.data.ShoppingTask
import com.life0423.carddock.platform.JdGateway
import com.life0423.carddock.platform.JdProduct
import com.life0423.carddock.platform.JD_PACKAGE
import com.life0423.carddock.platform.MEITUAN_PACKAGE
import com.life0423.carddock.platform.MeituanGateway
import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.model.RunPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import com.life0423.carddock.platform.isMarketWatch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.asStateFlow
import com.life0423.carddock.core.engine.RuleEngine
import com.life0423.carddock.core.session.PrototypeController
import com.life0423.carddock.data.AtomicFileCardStore
import com.life0423.carddock.data.AtomicTemplateStore
import com.life0423.carddock.demo.DemoDeviceGateway
import com.life0423.carddock.demo.TemplateCatalog
import com.life0423.carddock.platform.mika.PickupListHandoff
import com.life0423.carddock.platform.mika.MikaBackgroundQuery
import com.life0423.carddock.core.port.DeviceGateway
import com.life0423.carddock.core.port.DeviceResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * The single composition root: replace adapters here without changing UI or the rule engine.
 *
 * scope 用 Main.immediate，这是控制器要求的串行调度器。immediate 让已经在主线程上的点击不必再投递一圈。
 * SupervisorJob 让初始化失败不会取消其余子任务。
 */
class AppContainer(private val context: Context) {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val square = SquareStore(context, context.getString(R.string.square_endpoint), applicationScope)
    // 演示文件给人读，所以排版。未知字段显式拒绝，避免以后多出来的键被静默丢掉。
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = false }
    private val device = DemoDeviceGateway()
    internal val nativeDevice = MikaBackgroundQuery(context) { dockWindowFocused }
    private val meituan = MeituanGateway()
    @Volatile internal var dockWindowFocused = false
    private val jd = JdGateway(context) { dockWindowFocused }
    private val templates = TemplateCatalog.templates + listOf(pickupStatusTemplate, cainiaoEmptyTemplate, meituanTemplate, orderTotalTemplate, latestTakeoutTemplate) + jdTemplates
    private val modelSettings = ModelSettings(context)
    /** 外部迁移文件只能重新启用本机可还原的规则；学习过的 App 路线需重新试查。 */
    internal fun portableRule(rule: com.life0423.carddock.core.rule.RuleSpec): Boolean {
        val expected = when {
            rule.sourceId == TAOBAO && rule.targetKey.startsWith("books/") -> BookSearch.fromKey(rule.targetKey)?.template("")?.rule
            rule.sourceId == "carddock.reminder" && rule.localReminder != null -> {
                val r = requireNotNull(rule.localReminder)
                if (r.hour !in 0..23 || r.minute !in 0..59 || r.subject.isBlank() || r.subject.length > 80 ||
                    r.date?.let { runCatching { java.time.LocalDate.parse(it) }.isFailure } == true) return false
                nativeRule(rule.id,"carddock.reminder","scheduled","subject" to "提醒事项","time" to "计划时间").copy(localReminder=r)
            }
            rule.sourceId == com.life0423.carddock.platform.JD_PACKAGE && rule.targetKey == MONITOR_PAIR && rule.monitorComparison != null -> monitorTemplate(requireNotNull(rule.monitorComparison)).rule
            rule.sourceId == com.life0423.carddock.platform.APPLE_STORE && rule.targetKey.matches(Regex("[A-Za-z0-9]+CH/A")) -> watchRule(rule.targetKey)
            else -> (templates + listOf(meituanStatusTemplate, displayRankTemplate)).firstOrNull { it.rule.copy(id = rule.id) == rule }?.rule
        }
        return expected?.copy(id = rule.id) == rule
    }
    internal val reminders = com.life0423.carddock.platform.LocalAlarms(context)
    internal val marketWatch = com.life0423.carddock.platform.MarketWatch(context)
    private val shoppingStore = ShoppingStore(context)
    private val mutableTools = MutableStateFlow(PrototypeTools(shoppingFavorite =
        context.getSharedPreferences("space", Context.MODE_PRIVATE).getBoolean("shopping_favorite", false)))
    internal val tools = mutableTools.asStateFlow()
    private val routedDevice = object : DeviceGateway {
        override fun instruction(sourceId: String, options: ReadOptions): String = when {
            sourceId == com.life0423.carddock.ai.MCD_PACKAGE && options.meal != null -> "正在后台核验官方即时菜单，你可以继续使用手机。"
            sourceId == TAOBAO && options.countRoute == null && options.orderPath == null && options.reminderRoute == null -> "Mika 正在后台搜索淘宝并核对商品。可以切到其他应用，完成后回卡坞查看。"
            options.monitorComparison != null -> "正在核对两款京东显示器，并按所选用途生成建议…"
            options.orderPath != null -> "正在逐单核对近月外卖和付款凭证，请保持卡坞在前台。"
            options.appPrice -> "60 秒内切到京东匹配详情。打开分享，停一秒再复制链接，等详情恢复后返回卡坞。"
            sourceId == MEITUAN_PACKAGE && options.localTarget == null -> "正在后台核验最近外卖…"
            sourceId == MEITUAN_PACKAGE -> if (options.localTarget?.routeOid != null) "正在后台核验美团订单…"
                else "请在 25 秒内打开美团已完成订单并展开“订单信息”。已保存的卡可在详情配置后台路由。"
            sourceId == cainiaoEmptyTemplate.rule.sourceId -> "正在后台查询菜鸟待取状态…"
            else -> "正在读取页面"
        }

        override suspend fun inspect(sourceId: String, targetKey: String): DeviceResponse =
            inspect(sourceId, targetKey, ReadOptions())

        override suspend fun inspect(sourceId: String, targetKey: String, options: ReadOptions): DeviceResponse {
            if (sourceId == TAOBAO && targetKey.startsWith("books/")) return withContext(Dispatchers.IO) {
                val search = BookSearch.fromKey(targetKey) ?: return@withContext DeviceResponse.Failed("淘宝找书参数无效，请重新选择关键词和数量。")
                nativeDevice.books(search, modelSettings.load())
            }
            if (sourceId == LAUNCHER_SOURCE) return withContext(Dispatchers.IO) {
                val entry = context.launchableApps(targetKey.substringBefore('/')).resolveSaved(targetKey)
                if (entry == null) DeviceResponse.NeedsUser("应用识别失败：本机已找不到保存的应用入口。")
                else DeviceResponse.Ready(com.life0423.carddock.core.port.DeviceSnapshot(sourceId, targetKey,
                    mapOf("entry" to entry.label), "本机桌面启动入口"))
            }
            if (sourceId == "carddock.reminder") {
                val schedule = options.localReminder ?: return DeviceResponse.Failed("缺少提醒设置。")
                if (schedule.next(java.time.ZonedDateTime.now()) == null) return DeviceResponse.NeedsUser("这次提醒时间已过，请修改时间。")
                return DeviceResponse.Ready(com.life0423.carddock.core.port.DeviceSnapshot(sourceId, "scheduled",
                    mapOf("subject" to schedule.subject,"time" to schedule.label), "手机本地提醒计划"))
            }
            if (sourceId == com.life0423.carddock.ai.MCD_PACKAGE) {
                if (context.packageManager.getLaunchIntentForPackage(sourceId) == null)
                    return DeviceResponse.NeedsUser("未找到麦当劳官方 App。安装后可重新试运行。")
                val meal = options.meal
                return when {
                    meal == null && targetKey == "OPEN_MCDONALDS" -> DeviceResponse.Ready(com.life0423.carddock.core.port.DeviceSnapshot(
                        sourceId, "OPEN_MCDONALDS", mapOf("entry" to "麦当劳官方 App"), "本机应用入口"))
                    meal != null && targetKey in setOf(com.life0423.carddock.ai.EXPLORE_MEAL, com.life0423.carddock.core.rule.OfficialAction.ADD_MEAL.name) -> withContext(Dispatchers.IO) {
                        val config = modelSettings.load() ?: return@withContext DeviceResponse.NeedsUser("请在「我的」配置模型后重试，需求可保存为草稿。")
                        nativeDevice.exploreMeal(meal, config, cart = targetKey == com.life0423.carddock.core.rule.OfficialAction.ADD_MEAL.name)
                    }
                    meal != null && targetKey == meal.product -> withContext(Dispatchers.IO) { nativeDevice.meal(meal) }
                    meal != null && targetKey == com.life0423.carddock.core.rule.OfficialAction.REPEAT_MEAL.name ->
                        withContext(Dispatchers.IO) { nativeDevice.meal(meal, fixed = true) }
                    else -> DeviceResponse.NeedsUser("商品规则与模板不一致，请重新选择执行方式。")
                }
            }
            if (sourceId == com.life0423.carddock.platform.APPLE_STORE) return try {
                com.life0423.carddock.platform.loadDisplayCatalog().snapshot(targetKey)
            } catch (_: java.io.IOException) {
                DeviceResponse.NeedsUser("官网价格暂未核验，请检查连接后重试。")
            }
            if (sourceId == JD_PACKAGE && targetKey == MONITOR_PAIR) {
                val plan = options.monitorComparison ?: return DeviceResponse.Failed("缺少显示器用途，请重新创建模板。")
                return withContext(Dispatchers.IO) { com.life0423.carddock.platform.compareMonitors(jd, modelSettings, plan) }
            }
            if (targetKey == com.life0423.carddock.platform.MONTHLY_ORDERS) {
                val path = options.orderPath ?: return DeviceResponse.NeedsUser("缺少已核验的订单入口。")
                val until = java.time.Instant.ofEpochMilli(options.requestedAt ?: System.currentTimeMillis()).atZone(java.time.ZoneId.systemDefault())
                return withContext(Dispatchers.IO) {
                    nativeDevice.monthlyOrders(sourceId,com.life0423.carddock.platform.OrderCollection(sourceId,until,path,options.compareFilter))
                }
            }
            if (targetKey == com.life0423.carddock.platform.COUNT_TARGET) {
                val route = options.countRoute ?: return DeviceResponse.NeedsUser("缺少数量字段，请重新试运行。")
                return if (context.packageManager.getLaunchIntentForPackage(sourceId) == null) DeviceResponse.NeedsUser("找不到模板指定的应用。")
                else withContext(Dispatchers.IO) { nativeDevice.count(sourceId, route.page, route.subject, route) }
            }
            return when (sourceId) {
                cainiaoEmptyTemplate.rule.sourceId -> withContext(Dispatchers.IO) { nativeDevice.cainiao(sourceId, targetKey) }
                MEITUAN_PACKAGE -> if (targetKey == latestTakeoutTemplate.rule.targetKey) {
                    withContext(Dispatchers.IO) { nativeDevice.latestTakeout() }
                } else if (targetKey in setOf(meituanTemplate.rule.targetKey, orderTotalTemplate.rule.targetKey) && options.localTarget != null) {
                    // 页面树读取会同步查询系统服务，放到后台以便界面继续响应触摸。
                    withContext(Dispatchers.IO) {
                        val oid = options.localTarget?.routeOid
                        val total = targetKey == orderTotalTemplate.rule.targetKey
                        when {
                            oid != null -> nativeDevice.meituan(oid, total)
                            total -> MeituanGateway(observe = {
                                com.life0423.carddock.platform.CardDockAccessibilityService.instance?.observeDetail(MEITUAN_PACKAGE, geometry = true)
                            }).inspect(includeTotal = true)
                            else -> meituan.inspect()
                        }
                    }
                } else DeviceResponse.Failed("此卡片缺少本机授权订单号码，请重新创建。")
                JD_PACKAGE -> jd.inspect(targetKey, options.appPrice, options.requestedAt)
                else -> if (targetKey == "visible-reminder" && options.reminderRoute != null) {
                    val permitted = context.packageManager.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN)
                        .addCategory(android.content.Intent.CATEGORY_APP_CALENDAR), 0).any { it.activityInfo.packageName == sourceId }
                    if (!permitted) DeviceResponse.NeedsUser("请重新选择本机提醒来源。")
                    else withContext(Dispatchers.IO) { nativeDevice.reminderTrial(sourceId, options.reminderRoute) }
                } else device.inspect(sourceId, targetKey)
            }
        }
    }

    private val deviceLane = kotlinx.coroutines.sync.Mutex()
    val controller = PrototypeController(
        lane = deviceLane,
        // applicationContext：文件跟进程走，不跟某个 Activity。
        store = AtomicFileCardStore(context.applicationContext, json),
        templateStore = AtomicTemplateStore(context, json),
        compiler = MockRuleCompiler(templates),
        personalCompiler = PersonalRuleCompiler,
        verifyPickupList = { pickupList(PickupListHandoff.TRIAL) },
        verifyPickup = { entry -> withContext(Dispatchers.IO) {
            nativeDevice.pickupCode(entry).navigationIssue()
        } },
        discover = { demand, compareFilter ->
            val entries = withContext(Dispatchers.IO) { context.launchableApps() }
            val books = bookProposal(modelSettings, demand, entries)
            val launch = appProposal(demand, entries)
            if (books != null) books else if (launch != null && !launch.compound) launch.result else {
                val fallback = launch?.result
                val specialized = discoverPickup(nativeDevice, demand) ?: discoverReminder(context, modelSettings, nativeDevice, demand)
                    ?: discoverCount(context, modelSettings, nativeDevice, demand)
                    ?: discoverOrders(context, modelSettings, nativeDevice, demand, compareFilter)
                val knownSources = templates.map { it.rule.sourceId } + com.life0423.carddock.ai.MCD_PACKAGE
                val generic = fallback is com.life0423.carddock.core.port.DiscoveryResult.Plans &&
                    fallback.choices.none { it.rule.targetKey.substringBefore('/') in knownSources }
                val plan = specialized ?: if (generic) fallback else com.life0423.carddock.ai.planTemplate(modelSettings, demand, templates)
                if (plan is com.life0423.carddock.core.port.DiscoveryResult.Incomplete && plan.missingCapability != null) plan
                else if (plan is com.life0423.carddock.core.port.DiscoveryResult.Incomplete && fallback is com.life0423.carddock.core.port.DiscoveryResult.Plans)
                    fallback.copy(question = "${plan.reason} 也可以仅打开已识别的本机应用。")
                else if (plan is com.life0423.carddock.core.port.DiscoveryResult.Incomplete && specialized == null) fallback ?: plan
                else plan
            } },
        engine = RuleEngine(routedDevice),
        templates = templates,
        scope = applicationScope,
        setDeviceScenario = device::setScenario,
        modelCompiler = CainiaoModelCompiler(modelSettings),
        needsManualSequenceStart = { it.rule.sourceId == MEITUAN_PACKAGE && it.localTarget?.routeOid == null },
        replacementTemplate = ::cainiaoRuleChange,
    )

    /** 试运行和用户主动打开走同一交接路径，导航不修改卡片查询记录。 */
    internal suspend fun openPickupList() = runNavigation { pickupList(PickupListHandoff.OPEN) }

    /** 仅主动加购按钮提供确认回调，刷新与后台调度始终使用只读入口。 */
    internal suspend fun addMeal(target: com.life0423.carddock.core.rule.MealTarget, approve: suspend (String) -> Boolean) = runNavigation {
        val config = modelSettings.load() ?: return@runNavigation DeviceResponse.NeedsUser("请先配置模型。")
        withContext(Dispatchers.IO) { nativeDevice.exploreMeal(target, config, cart=true, approve=approve).navigationIssue() }
    }

    /** 主动点开固定搭配卡时，从同店即时菜单重新准备并交接官方详情。 */
    internal suspend fun openFixedMeal(target: com.life0423.carddock.core.rule.MealTarget) = runNavigation {
        withContext(Dispatchers.IO) { nativeDevice.meal(target, fixed = true, openFixedDetail = true).navigationIssue() }
    }

    /** 列表和亮码都可能切换主屏，先取得与试查共用的设备通道。 */
    internal suspend fun runNavigation(action: suspend () -> DeviceResponse.NeedsUser?): DeviceResponse.NeedsUser? {
        if (!deviceLane.tryLock()) return DeviceResponse.NeedsUser("正在核验其他操作，请稍后重试。")
        return try { action() } finally { deviceLane.unlock() }
    }

    private suspend fun pickupList(mode: PickupListHandoff): DeviceResponse.NeedsUser? =
        withContext(Dispatchers.IO) {
            nativeDevice.cainiao(com.life0423.carddock.platform.CAINIAO_PACKAGE,
                com.life0423.carddock.platform.CAINIAO_STATUS_TARGET, mode).navigationIssue()
        }

    // 不阻塞构造。读成功才发布磁盘或种子；读失败则结束 loading，集合为空且不可写。
    init {
        applicationScope.launch { controller.initialize() }
        applicationScope.launch {
            controller.state.map { if (it.loading || !it.cardsReadable) null else it.cards.mapNotNull { c ->
                c.rule.localReminder?.let { schedule -> c.id to schedule }
            }.toMap() }.distinctUntilChanged().collect { if (it != null) reminders.sync(it) }
        }
        applicationScope.launch {
            controller.state.map { if (it.loading || !it.cardsReadable) null else it.cards.filter { card -> card.isMarketWatch() } }.distinctUntilChanged().collect {
                if (it != null) marketWatch.sync(it)
            }
        }
        applicationScope.launch {
            try {
                val model = modelSettings.load()?.let { ModelSummary(it.endpoint, it.model) }
                mutableTools.value = mutableTools.value.copy(model = model)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableTools.value = mutableTools.value.copy(modelMessage = "配置无法读取，请重新保存。", modelCheck = ModelCheck.UNREADABLE)
            } finally {
                mutableTools.value = mutableTools.value.copy(modelBusy = false)
            }
        }
        applicationScope.launch {
            try {
                val shopping = shoppingStore.load()
                mutableTools.value = mutableTools.value.copy(shopping = shopping)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableTools.value = mutableTools.value.copy(shoppingMessage = "选购任务无法读取，原文件已保留。")
            } finally {
                mutableTools.value = mutableTools.value.copy(shoppingBusy = false)
            }
        }
    }

    internal fun saveModel(endpoint: String, model: String, key: String) = updateModel("模型配置已保存") {
        modelSettings.save(endpoint, model, key)
    }

    internal fun clearModel() = updateModel("模型配置已清除") {
        modelSettings.clear()
        null
    }

    /** 由用户点按触发，使用与模板相同的加密配置和请求通道，只发送固定的测试文本。 */
    internal fun checkModel() {
        val state = mutableTools.value
        if (state.modelBusy || state.model == null) return
        mutableTools.value = state.copy(modelBusy = true, modelCheck = ModelCheck.CHECKING)
        applicationScope.launch {
            val status = try {
                val config = checkNotNull(modelSettings.load())
                val reply = withContext(Dispatchers.IO) { requestModel(config, "连接测试，请只回复 OK。") }
                if (reply.isBlank()) ModelCheck.FAILED else ModelCheck.AVAILABLE
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ModelCheck.FAILED
            }
            mutableTools.value = mutableTools.value.copy(modelBusy = false, modelCheck = status)
        }
    }

    /** 同一个设置表单内顺序执行保存和清除。 */
    private fun updateModel(message: String, update: suspend () -> ModelSummary?) {
        if (mutableTools.value.modelBusy) return
        mutableTools.value = mutableTools.value.copy(modelBusy = true)
        applicationScope.launch {
            try {
                val model = update()
                mutableTools.value = mutableTools.value.copy(model = model, modelMessage = message, modelCheck = ModelCheck.UNCHECKED)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableTools.value = mutableTools.value.copy(modelMessage = "无法更新配置，请检查 HTTPS 地址、模型名和密钥。")
            } finally {
                mutableTools.value = mutableTools.value.copy(modelBusy = false)
            }
        }
    }

    /** 收藏只影响入口顺序，和选购快照分别保存。 */
    internal fun toggleShoppingFavorite() {
        val favorite = !mutableTools.value.shoppingFavorite
        context.getSharedPreferences("space", Context.MODE_PRIVATE).edit()
            .putBoolean("shopping_favorite", favorite).apply()
        mutableTools.value = mutableTools.value.copy(shoppingFavorite = favorite)
    }

    /** 路由号与本机卡片一起保存，不进入模板或快照。 */
    internal fun saveMeituanRoute(cardId: String, oid: String?): Boolean {
        if (oid != null && !oid.matches(Regex("[0-9]{19}"))) return false
        val card = controller.state.value.cards.firstOrNull { it.id == cardId }
        if (card?.rule?.sourceId != MEITUAN_PACKAGE || card.localTarget == null) return false
        controller.updateLocalRoute(cardId, oid)
        return true
    }

    internal fun saveShopping(note: String) {
        if (mutableTools.value.shoppingBusy) return
        val cards = controller.state.value.cards
        val a = currentShoppingItem(cards, JdProduct.A) ?: return
        val b = currentShoppingItem(cards, JdProduct.B) ?: return
        val task = ShoppingTask(a, b, note.take(500))
        mutableTools.value = mutableTools.value.copy(shoppingBusy = true)
        applicationScope.launch {
            try {
                shoppingStore.save(task)
                mutableTools.value = mutableTools.value.copy(shopping = task, shoppingMessage = "选购任务已保存")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableTools.value = mutableTools.value.copy(shoppingMessage = "保存失败，原任务已保留。")
            } finally {
                mutableTools.value = mutableTools.value.copy(shoppingBusy = false)
            }
        }
    }
}

/** 导航调用只需知道是否完成，以及失败时能执行的恢复动作。 */
internal fun DeviceResponse.navigationIssue(): DeviceResponse.NeedsUser? = when (this) {
    is DeviceResponse.Ready -> null
    is DeviceResponse.NeedsUser -> this
    is DeviceResponse.Failed -> DeviceResponse.NeedsUser(message)
}

/** 仅表示当前进程最近一次主动测试，修改配置后回到待测试。 */
internal enum class ModelCheck(val label: String) {
    UNREADABLE("配置无法读取"), UNCHECKED("待测试"), CHECKING("测试中"), AVAILABLE("上次测试可用"), FAILED("上次测试失败")
}

/** 两项工具状态随应用进程共享，配置摘要不含密钥。 */
internal data class PrototypeTools(
    val model: ModelSummary? = null,
    val modelBusy: Boolean = true,
    val modelCheck: ModelCheck = ModelCheck.UNCHECKED,
    val modelMessage: String? = null,
    val shopping: ShoppingTask? = null,
    val shoppingBusy: Boolean = true,
    val shoppingMessage: String? = null,
    val shoppingFavorite: Boolean = false,
)

internal fun shoppingCard(cards: List<CardRecord>, product: JdProduct): CardRecord? = cards
    .filter { it.rule.sourceId == JD_PACKAGE && it.rule.targetKey == product.sku && it.snapshot != null }
    .maxByOrNull { it.snapshot!!.fetchedAt }

internal fun currentShoppingItem(cards: List<CardRecord>, product: JdProduct): ShoppingItem? {
    val card = shoppingCard(cards, product) ?: return null
    if (card.run?.phase != RunPhase.SUCCEEDED) return null
    return ShoppingItem(product.sku, checkNotNull(card.snapshot), card.appPrice, card.priceVerificationFailed)
}
