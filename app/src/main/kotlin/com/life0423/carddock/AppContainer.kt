package com.life0423.carddock

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
import kotlinx.coroutines.flow.asStateFlow
import com.life0423.carddock.core.engine.RuleEngine
import com.life0423.carddock.core.session.PrototypeController
import com.life0423.carddock.data.AtomicFileCardStore
import com.life0423.carddock.data.AtomicTemplateStore
import com.life0423.carddock.demo.DemoDeviceGateway
import com.life0423.carddock.demo.TemplateCatalog
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
    private val templates = TemplateCatalog.templates + listOf(cainiaoEmptyTemplate, meituanTemplate, latestTakeoutTemplate) + jdTemplates
    private val modelSettings = ModelSettings(context)
    private val shoppingStore = ShoppingStore(context)
    private val mutableTools = MutableStateFlow(PrototypeTools(shoppingFavorite =
        context.getSharedPreferences("space", Context.MODE_PRIVATE).getBoolean("shopping_favorite", false)))
    internal val tools = mutableTools.asStateFlow()
    private val routedDevice = object : DeviceGateway {
        override fun instruction(sourceId: String, options: ReadOptions): String = when {
            options.appPrice -> "60 秒内切到京东匹配详情。打开分享，停一秒再复制链接，等详情恢复后返回卡坞。"
            sourceId == MEITUAN_PACKAGE && options.localTarget == null -> "正在后台核验最近外卖…"
            sourceId == MEITUAN_PACKAGE -> if (options.localTarget?.routeOid != null) "正在后台核验美团订单…"
                else "请在 25 秒内打开美团已完成订单并展开“订单信息”。已保存的卡可在详情配置后台路由。"
            sourceId == cainiaoEmptyTemplate.rule.sourceId -> "正在后台查询菜鸟待取状态…"
            else -> "正在读取页面"
        }

        override suspend fun inspect(sourceId: String, targetKey: String): DeviceResponse =
            inspect(sourceId, targetKey, ReadOptions())

        override suspend fun inspect(sourceId: String, targetKey: String, options: ReadOptions): DeviceResponse = when (sourceId) {
            cainiaoEmptyTemplate.rule.sourceId -> withContext(Dispatchers.IO) { nativeDevice.cainiao(sourceId, targetKey) }
            MEITUAN_PACKAGE -> if (targetKey == latestTakeoutTemplate.rule.targetKey) {
                withContext(Dispatchers.IO) { nativeDevice.latestTakeout() }
            } else if (targetKey == meituanTemplate.rule.targetKey && options.localTarget != null) {
                // 页面树读取会同步查询系统服务，放到后台以便界面继续响应触摸。
                withContext(Dispatchers.IO) {
                    val oid = options.localTarget?.routeOid
                    if (oid == null) meituan.inspect() else nativeDevice.meituan(oid)
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

    val controller = PrototypeController(
        // applicationContext：文件跟进程走，不跟某个 Activity。
        store = AtomicFileCardStore(context.applicationContext, json),
        templateStore = AtomicTemplateStore(context, json),
        compiler = MockRuleCompiler(templates),
        personalCompiler = PersonalRuleCompiler,
        discover = { demand -> discoverReminder(context, modelSettings, nativeDevice, demand) },
        engine = RuleEngine(routedDevice),
        templates = templates,
        scope = applicationScope,
        setDeviceScenario = device::setScenario,
        modelCompiler = CainiaoModelCompiler(modelSettings),
        needsManualSequenceStart = { it.rule.sourceId == MEITUAN_PACKAGE && it.localTarget?.routeOid == null },
        replacementTemplate = ::cainiaoRuleChange,
    )

    // 不阻塞构造。读成功才发布磁盘或种子；读失败则结束 loading，集合为空且不可写。
    init {
        applicationScope.launch { controller.initialize() }
        applicationScope.launch {
            try {
                val model = modelSettings.load()?.let { ModelSummary(it.endpoint, it.model) }
                mutableTools.value = mutableTools.value.copy(model = model)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableTools.value = mutableTools.value.copy(modelMessage = "配置无法读取，请重新保存。")
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

    /** 同一个设置表单内顺序执行保存和清除。 */
    private fun updateModel(message: String, update: suspend () -> ModelSummary?) {
        if (mutableTools.value.modelBusy) return
        mutableTools.value = mutableTools.value.copy(modelBusy = true)
        applicationScope.launch {
            try {
                val model = update()
                mutableTools.value = mutableTools.value.copy(model = model, modelMessage = message)
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

/** 两项工具状态随应用进程共享，配置摘要不含密钥。 */
internal data class PrototypeTools(
    val model: ModelSummary? = null,
    val modelBusy: Boolean = true,
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
