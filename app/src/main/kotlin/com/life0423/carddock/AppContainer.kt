package com.life0423.carddock

import android.content.Context
import com.life0423.carddock.ai.MockRuleCompiler
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
import com.life0423.carddock.demo.DemoDeviceGateway
import com.life0423.carddock.demo.TemplateCatalog
import com.life0423.carddock.platform.CainiaoDeviceGateway
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
class AppContainer(context: Context) {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // 演示文件给人读，所以排版。未知字段显式拒绝，避免以后多出来的键被静默丢掉。
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = false }
    private val device = DemoDeviceGateway()
    private val nativeDevice = CainiaoDeviceGateway()
    private val meituan = MeituanGateway()
    internal var dockWindowFocused = false
    private val jd = JdGateway(context) { dockWindowFocused }
    private val templates = TemplateCatalog.templates + listOf(cainiaoEmptyTemplate, meituanTemplate) + jdTemplates
    private val modelSettings = ModelSettings(context)
    private val shoppingStore = ShoppingStore(context)
    private val mutableTools = MutableStateFlow(PrototypeTools())
    internal val tools = mutableTools.asStateFlow()
    private val routedDevice = object : DeviceGateway {
        override fun instruction(sourceId: String, options: ReadOptions): String = when {
            options.appPrice -> "60 秒内切到京东匹配详情。打开分享，停一秒再复制链接，等详情恢复后返回卡坞。"
            sourceId == MEITUAN_PACKAGE -> "请在 25 秒内从最近任务切回美团，打开对应已完成订单并展开“订单信息”。"
            sourceId == cainiaoEmptyTemplate.rule.sourceId -> "请在 20 秒内切到菜鸟“取包裹”页。"
            else -> "正在读取页面"
        }

        override suspend fun inspect(sourceId: String, targetKey: String): DeviceResponse =
            inspect(sourceId, targetKey, ReadOptions())

        override suspend fun inspect(sourceId: String, targetKey: String, options: ReadOptions): DeviceResponse = when (sourceId) {
            cainiaoEmptyTemplate.rule.sourceId -> withContext(Dispatchers.IO) { nativeDevice.inspect(sourceId, targetKey) }
            MEITUAN_PACKAGE -> if (targetKey == meituanTemplate.rule.targetKey && options.localTarget != null) {
                // 页面树读取会同步查询系统服务，放到后台以便界面继续响应触摸。
                withContext(Dispatchers.IO) { meituan.inspect() }
            } else DeviceResponse.Failed("此卡片缺少本机授权订单号码，请重新创建。")
            JD_PACKAGE -> jd.inspect(targetKey, options.appPrice, options.requestedAt)
            else -> device.inspect(sourceId, targetKey)
        }
    }

    val controller = PrototypeController(
        // applicationContext：文件跟进程走，不跟某个 Activity。
        store = AtomicFileCardStore(context.applicationContext, json),
        compiler = MockRuleCompiler(templates),
        engine = RuleEngine(routedDevice),
        templates = templates,
        scope = applicationScope,
        setDeviceScenario = device::setScenario,
        modelCompiler = CainiaoModelCompiler(modelSettings),
        needsManualSequenceStart = { it.rule.sourceId in setOf(cainiaoEmptyTemplate.rule.sourceId, MEITUAN_PACKAGE) },
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
)

internal fun shoppingCard(cards: List<CardRecord>, product: JdProduct): CardRecord? = cards
    .filter { it.rule.sourceId == JD_PACKAGE && it.rule.targetKey == product.sku && it.snapshot != null }
    .maxByOrNull { it.snapshot!!.fetchedAt }

internal fun currentShoppingItem(cards: List<CardRecord>, product: JdProduct): ShoppingItem? {
    val card = shoppingCard(cards, product) ?: return null
    if (card.run?.phase != RunPhase.SUCCEEDED) return null
    return ShoppingItem(product.sku, checkNotNull(card.snapshot), card.appPrice, card.priceVerificationFailed)
}
