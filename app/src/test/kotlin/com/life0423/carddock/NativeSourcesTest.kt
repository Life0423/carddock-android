package com.life0423.carddock

import com.life0423.carddock.ai.PersonalRuleCompiler
import com.life0423.carddock.ai.compileCainiaoCandidate
import com.life0423.carddock.core.rule.RuleStep
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
import com.life0423.carddock.platform.latestTakeoutRow
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
    /** 详情意图只提供已实现的列表，无对象绑定。状态查询及复合操作分别留在原有分支。 */
    @Test fun pickupNavigationPreservesQueryAndCodeIntents() = runTest {
        for (request in listOf("在菜鸟打开我选的待取包裹", "请帮我查看菜鸟待取包裹列表", "打开菜鸟包裹详情")) {
            val template = (PersonalRuleCompiler.compile(request) as CompileResult.Success).template
            assertTrue(template.opensPickupList)
            assertNull(template.pickupEntry)
            assertEquals("进入菜鸟后选择包裹", template.description)
        }
        val query = (PersonalRuleCompiler.compile("查看菜鸟待取包裹") as CompileResult.Success).template
        assertFalse(query.opensPickupList)
        assertEquals(pickupStatusTemplate.rule, query.rule)
        val empty = (PersonalRuleCompiler.compile("核验菜鸟暂无待取包裹") as CompileResult.Success).template
        assertEquals(cainiaoEmptyTemplate.rule, empty.rule)
        assertTrue(PersonalRuleCompiler.compile("打开菜鸟快递") is CompileResult.Unsupported)
        assertTrue(PersonalRuleCompiler.compile("打开菜鸟待取列表并转发") is CompileResult.Unsupported)
    }

    @Test fun personalDemandBuildsAStatusOnlyRule() = runTest {
        val candidate = PersonalRuleCompiler.compile("只显示美团订单状态") as CompileResult.Success
        assertTrue(candidate.template.requiresOrderNumber)
        assertEquals(listOf("status"), candidate.template.rule.steps.filterIsInstance<RuleStep.ReadField>().map { it.outputKey })
        assertTrue(PersonalRuleCompiler.compile("替我取消美团订单") is CompileResult.Unsupported)
        assertTrue(PersonalRuleCompiler.compile("读取所有真实取件码") is CompileResult.Unsupported)
    }

    @Test fun naturalDemandKeepsProductIdentityAndProjectsOrderedFields() = runTest {
        val result = PersonalRuleCompiler.compile("请帮我看看京东商品 A，只显示商品名称和规格") as CompileResult.Success
        assertEquals(listOf("title", "variant"), result.template.display.map { it.key })
        assertEquals(listOf("sku", "variant", "title"), result.template.rule.steps.filterIsInstance<RuleStep.ReadField>().map { it.outputKey })
        assertTrue(PersonalRuleCompiler.compile("帮我看看菜鸟有没有待取包裹") is CompileResult.Success)
        assertTrue(PersonalRuleCompiler.compile("帮我看看京东商品 A 的规格和帮我下单") is CompileResult.Unsupported)
        val latest = PersonalRuleCompiler.compile("查看美团最近一笔外卖") as CompileResult.Success
        assertEquals("meituan-latest", latest.template.id)
        assertFalse(latest.template.requiresOrderNumber)
    }

    /** 两行状态与日期分别位于兄弟节点。模拟同分钟并列、第二行缺证及列表被裁切。 */
    @Test fun latestTakeoutRequiresACompleteUnambiguousOrderInEachRow() {
        fun node(parent: Int, label: String, box: android.graphics.Rect, role: String = "") =
            DetailNode(parent, null, role, label, true, box)
        val first = "下单时间：2026-11-18 12:00"
        val newest = "下单时间：2026-11-18 12:01"
        val nodes = listOf(
            node(-1,"",android.graphics.Rect(0,0,300,20)),
            node(0,"搜索我的订单",android.graphics.Rect(20,1,100,18)),
            node(-1,"",android.graphics.Rect(0,20,300,500),"android.widget.ScrollView"),
            node(2,"",android.graphics.Rect(0,20,300,220)),
            node(3,"",android.graphics.Rect(0,20,300,120)),
            node(4,"已完成",android.graphics.Rect(20,21,100,34)),
            node(3,first,android.graphics.Rect(20,45,180,80)),
            node(3,"",android.graphics.Rect(0,120,300,220)),
            node(7,"已完成",android.graphics.Rect(20,121,100,140)),
            node(3,newest,android.graphics.Rect(20,145,180,180)),
        )
        val page = DetailWindow(MEITUAN_PACKAGE, nodes, false)
        assertEquals(newest, page.latestTakeoutRow()?.label)
        fun changed(index: Int, replacement: DetailNode) = page.copy(nodes = nodes.toMutableList().apply { set(index,replacement) })
        assertNull(changed(9,nodes[9].copy(label=first)).latestTakeoutRow())
        assertNull(changed(8,nodes[8].copy(bounds=nodes[5].bounds)).latestTakeoutRow())
        val shifted = nodes.map { n -> if (n.parent == 3 && n.bounds == nodes[4].bounds) n.copy(bounds=android.graphics.Rect(0,21,300,120)) else n }
        val leading = node(3,"配送中",android.graphics.Rect(3,20,100,21))
        assertNull(page.copy(nodes=shifted + leading).latestTakeoutRow())
        assertNull(changed(2,nodes[2].copy(scrollable=true)).latestTakeoutRow())
        assertNull(changed(2,nodes[2].copy(bounds=android.graphics.Rect(0,20,300,219))).latestTakeoutRow())
    }

    @Test fun meituanUsesActualNumberAndRequiresTime() = runTest {
        val sample = "0001234567890123456"
        fun row(parent: Int, label: String = "", id: String? = null) = DetailNode(parent, id, null, label, true)
        var window = DetailWindow(MEITUAN_PACKAGE, emptyList(), false)
        val meituan = MeituanGateway({ true }, { window })
        val engine = RuleEngine(object : DeviceGateway {
            override suspend fun inspect(sourceId: String, targetKey: String) = meituan.inspect(includeTotal = targetKey == orderTotalTemplate.rule.targetKey)
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
            // 折叠行的第一次点击被宿主吞掉，即使返回成功，也仅用出现的字段作为通过依据。
            var clicks = 0
            val retry = MeituanGateway(connected = { true }, observe = {
                window.copy(nodes = if (clicks < 2) nodes.take(6) + row(4, "配送骑手/支付方式等") else nodes)
            }, expand = { clicks++; true })
            assertTrue(retry.inspect() is DeviceResponse.Ready)
            assertEquals(2, clicks)
            window = window.copy(nodes = nodes)
            val good = engine.execute(rule, ReadOptions(LocalTarget(sample))) as EngineResult.Success
            assertEquals(listOf("订单已完成", "2026-01-02 12:34:56"), good.snapshot.fields.map { it.value })
            assertEquals("TARGET_MISMATCH",
                (engine.execute(rule, ReadOptions(LocalTarget("0001234567890123457"))) as EngineResult.Failure).code)
            // 只有本订单详情内的合计可用于金额卡。外层推荐商品的价格不属于本次快照。
            val box = android.graphics.Rect(0, 30, 120, 60)
            val total = row(3, "合计：¥12.50").copy(bounds = box)
            val outside = row(-1, "合计：¥8.00").copy(bounds = box)
            window = window.copy(nodes = nodes + total + outside)
            val amountRule = (PersonalRuleCompiler.compile("查看美团历史订单的合计金额") as CompileResult.Success).template.rule
            val amount = engine.execute(amountRule, ReadOptions(LocalTarget(sample))) as EngineResult.Success
            assertEquals(listOf("¥12.50", "人民币（CNY）", "2026-01-02 12:34:56", "订单已完成"), amount.snapshot.fields.map { it.value })
            window = window.copy(nodes = nodes + outside)
            assertTrue(engine.execute(amountRule, ReadOptions(LocalTarget(sample))) is EngineResult.NeedsUser)
            window = window.copy(nodes = nodes.dropLast(1))
            assertTrue(engine.execute(rule, ReadOptions(LocalTarget(sample))) is EngineResult.NeedsUser)
            window = window.copy(nodes = nodes.mapIndexed { index, node -> if (index == 9) node.copy(parent = 3) else node })
            assertTrue(engine.execute(rule, ReadOptions(LocalTarget(sample))) is EngineResult.NeedsUser)
            if (list != null) {
                // vivo 后台屏上，完成状态位于同一订单列表的独立状态子树。
                val background = nodes.mapIndexed { index, node -> if (index == 2) node.copy(label = "") else node } +
                    listOf(row(3, id = "$MEITUAN_PACKAGE:id/o73"),
                        row(12, id = "$MEITUAN_PACKAGE:id/gme"), row(13, "订单已完成"))
                window = window.copy(nodes = background)
                assertTrue(engine.execute(rule, ReadOptions(LocalTarget(sample))) is EngineResult.Success)
                window = window.copy(nodes = background.mapIndexed { index, node ->
                    if (index == 12) node.copy(parent = 0) else node
                })
                assertTrue(engine.execute(rule, ReadOptions(LocalTarget(sample))) is EngineResult.NeedsUser)
            }
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
