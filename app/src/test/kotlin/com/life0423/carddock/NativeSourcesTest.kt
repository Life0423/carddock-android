package com.life0423.carddock

import com.life0423.carddock.ai.compileCainiaoCandidate
import com.life0423.carddock.core.engine.EngineResult
import com.life0423.carddock.core.engine.RuleEngine
import com.life0423.carddock.core.model.AppPrice
import com.life0423.carddock.core.model.CardSnapshot
import com.life0423.carddock.core.model.FieldValue
import com.life0423.carddock.core.model.LocalTarget
import com.life0423.carddock.core.port.CompileResult
import com.life0423.carddock.core.port.DeviceGateway
import com.life0423.carddock.core.port.DeviceResponse
import com.life0423.carddock.core.port.ReadOptions
import com.life0423.carddock.data.ShoppingItem
import com.life0423.carddock.data.ShoppingStore
import com.life0423.carddock.data.ShoppingTask
import com.life0423.carddock.data.priceChange
import com.life0423.carddock.platform.DetailNode
import com.life0423.carddock.platform.DetailWindow
import com.life0423.carddock.platform.JD_PACKAGE
import com.life0423.carddock.platform.JdGateway
import com.life0423.carddock.platform.JdProduct
import com.life0423.carddock.platform.ShareClip
import com.life0423.carddock.platform.MEITUAN_PACKAGE
import com.life0423.carddock.platform.MeituanGateway
import com.life0423.carddock.platform.verifyJdPage
import android.app.Application
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 用脱敏详情结构测试真实适配器、引擎及选购恢复。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class NativeSourcesTest {
    @Test fun meituanUsesActualNumberAndRequiresTime() = runTest {
        val sample = "0001234567890123456"
        fun row(parent: Int, label: String = "", id: String? = null) = DetailNode(parent, id, null, label, true)
        var window = DetailWindow(MEITUAN_PACKAGE, emptyList(), false)
        val meituan = MeituanGateway({ true }, { window })
        val engine = RuleEngine(object : DeviceGateway {
            override suspend fun inspect(sourceId: String, targetKey: String) = meituan.inspect()
        })
        val rule = meituanTemplate.rule
        for ((root, header, list) in listOf(Triple("ifs", "r19", null), Triple("ii7", "sur", "kr1"))) {
            val nodes = listOf(
                row(-1, id = "$MEITUAN_PACKAGE:id/$root").copy(visible = list == null),
                row(0, id = "$MEITUAN_PACKAGE:id/$header").copy(visible = list == null), row(1, "订单已完成"),
                row(0, id = list?.let { "$MEITUAN_PACKAGE:id/$it" }),
                row(3), row(4, "订单信息"),
                row(4), row(6, "订单号码"), row(6, sample),
                row(4), row(9, "下单时间"), row(9, "2026-01-02 12:34:56"),
            )
            window = window.copy(nodes = nodes)
            val good = engine.execute(rule, ReadOptions(LocalTarget(sample))) as EngineResult.Success
            assertEquals(listOf("订单已完成", "2026-01-02 12:34:56"), good.snapshot.fields.map { it.value })
            assertEquals("TARGET_MISMATCH",
                (engine.execute(rule, ReadOptions(LocalTarget("0001234567890123457"))) as EngineResult.Failure).code)
            window = window.copy(nodes = nodes.dropLast(1))
            assertTrue(engine.execute(rule, ReadOptions(LocalTarget(sample))) is EngineResult.NeedsUser)
            window = window.copy(nodes = nodes.mapIndexed { index, node -> if (index == 9) node.copy(parent = 3) else node })
            assertTrue(engine.execute(rule, ReadOptions(LocalTarget(sample))) is EngineResult.NeedsUser)
        }
    }

    @Test fun jdRejectsWrongSkuVariantAndMissingName() {
        val a = JdProduct.A
        fun page(sku: String, variant: String, title: String) =
            """window._itemOnly = ({"item":{"skuId":"$sku","skuName":"$title","newColorSize":[{"skuId":"$sku","1":"$variant"}]}});"""
        val verified = verifyJdPage(page(a.sku, a.variant, "显示器"), a) as DeviceResponse.Ready
        assertEquals(a.sku, verified.snapshot.targetKey)
        assertNull(verified.snapshot.appPrice)
        assertTrue(verifyJdPage(page(JdProduct.B.sku, a.variant, "显示器"), a) is DeviceResponse.Failed)
        assertTrue(verifyJdPage(page(a.sku, "错规格", "显示器"), a) is DeviceResponse.Failed)
        assertTrue(verifyJdPage(page(a.sku, a.variant, ""), a) is DeviceResponse.Failed)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun appPriceRequiresFreshMatchingShareAndDetailAfterShare() = runTest {
        val product = JdProduct.B
        val dock = "carddock"
        fun node(parent: Int, label: String = "", role: String? = null, id: String? = null) =
            DetailNode(parent, id, role, label, true)
        val detail = DetailWindow(JD_PACKAGE, listOf(
            node(-1, id = "com.jd.lib.productdetail.feature:id/a6c"),
            node(0, "已选中款式 " + product.appVariant, "android.widget.Button"),
            node(0, product.titlePrefix + " 显示器", "android.widget.TextView"),
            node(0), node(3), node(4), node(5, "7499元，同款低价", "android.view.ViewGroup"),
            node(3, "已直降500元"),
        ), false)
        val share = DetailWindow(JD_PACKAGE, listOf(node(-1, "复制链接")), false)
        val returned = DetailWindow(dock, emptyList(), false)
        suspend fun attempt(
            copiedAt: Long,
            matches: Boolean,
            seeDetailAfterCopy: Boolean,
            returnWindow: DetailWindow? = returned,
            dockFocused: Boolean = false,
        ): DeviceResponse {
            val start = testScheduler.currentTime + 100
            val gateway = JdGateway(dock, { ShareClip("测试链接", start + copiedAt) }, { true }, {
                when (100 + testScheduler.currentTime - start) {
                    in 0..199 -> detail
                    in 200..399 -> share
                    in 400..599 -> if (seeDetailAfterCopy) detail else share
                    else -> returnWindow
                }
            }, { 100 + testScheduler.currentTime }, { _, _ -> matches }, {
                val variantJson = kotlinx.serialization.json.JsonPrimitive(product.variant)
                """window._itemOnly = ({"item":{"skuId":"${product.sku}","skuName":"显示器","newColorSize":[{"skuId":"${product.sku}","1":$variantJson}]}});"""
            }, dockWindowFocused = { dockFocused })
            return gateway.inspect(product.sku, appPrice = true, requestedAt = start)
        }
        val good = attempt(300, matches = true, seeDetailAfterCopy = true) as DeviceResponse.Ready
        assertEquals("7499", good.snapshot.appPrice?.amount)
        assertEquals("同款低价", good.snapshot.appPrice?.label)
        assertEquals("已直降500元", good.snapshot.appPrice?.discount)
        assertEquals(product.variant, good.snapshot.appPrice?.variant)
        assertTrue(attempt(100, matches = true, seeDetailAfterCopy = true) is DeviceResponse.NeedsUser)
        assertTrue(attempt(300, matches = false, seeDetailAfterCopy = true) is DeviceResponse.Failed)
        assertTrue(attempt(300, matches = true, seeDetailAfterCopy = false) is DeviceResponse.NeedsUser)
        assertTrue(attempt(300, true, true, returnWindow = null, dockFocused = true) is DeviceResponse.Ready)
        assertTrue(attempt(300, true, true, returnWindow = null) is DeviceResponse.NeedsUser)
    }

    @Test fun shoppingRestoresSavedBaselineAndRequiresNewComparablePrice() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val a = JdProduct.A
        val b = JdProduct.B
        fun item(product: JdProduct) = ShoppingItem(product.sku,
            CardSnapshot(listOf(FieldValue("variant", "规格", product.variant),
                FieldValue("title", "商品名称", product.title)), 10),
            AppPrice("3000", "CNY", product.variant, "到手价", "满100已减10", 20))
        val task = ShoppingTask(item(a), item(b), "比较记录")
        ShoppingStore(context).save(task)
        val restored = checkNotNull(ShoppingStore(context).load())
        assertEquals(task, restored)
        val old = restored.a
        val price = checkNotNull(old.price)
        assertEquals("无法核验", priceChange(old, old))
        assertEquals("无法核验", priceChange(old.copy(price = price.copy(verifiedAt = 30, discount = null)), old))
        assertEquals("未变化", priceChange(old.copy(price = price.copy(verifiedAt = 30)), old))
        assertEquals("有变化 · 下跌", priceChange(old.copy(price = price.copy(amount = "2999", verifiedAt = 30)), old))
    }

    @Test fun modelOnlyAcceptsSupportedCainiaoRule() {
        val rule = cainiaoEmptyTemplate.rule
        val candidate = Json.encodeToString(com.life0423.carddock.core.rule.RuleSpec.serializer(), rule.copy(id = "model-cainiao"))
        assertTrue(compileCainiaoCandidate(candidate) is CompileResult.Success)
        assertTrue(compileCainiaoCandidate(candidate.replace("readField", "click")) is CompileResult.Unsupported)
        assertTrue(compileCainiaoCandidate(candidate.replace("com.cainiao.wireless", "demo.express")) is CompileResult.Unsupported)
        assertTrue(compileCainiaoCandidate(candidate.replace("status", "secret")) is CompileResult.Unsupported)
    }
}
