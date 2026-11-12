package com.wangzan101.mika.experiment

import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test

class DetailEvidenceTest {
    @Test fun jdShareRequiresProductAndVariantMatchAndLeavesPriceUnverified() {
        val html = """<script>window._itemOnly = ({
            "item":{"skuId":"$JD_A_SKU","skuName":"显示器","newColorSize":[{"skuId":"$JD_A_SKU","1":"$JD_A_VARIANT"}]}
        });</script><script>{"price":"4??9"}</script>"""
        val verifier = JdShareVerifier()
        val result = verifier.verifyPage(html)
        assertEquals("JD_IDENTITY_CONFIRMED", result.outcome)
        assertTrue(result.displayText.endsWith("价格未核验"))
        assertEquals("IDENTITY_MISMATCH", verifier.verifyPage(html.replace(JD_A_VARIANT, "31.5英寸")).outcome)
        assertEquals("IDENTITY_MISMATCH", verifier.verifyPage(html.replaceFirst(JD_A_SKU, "999999999999")).outcome)
        assertFalse(verifier.verifyPage("<html>登录</html>").successful)
    }

    private fun node(index: Int, parent: Int, text: String? = null, id: String? = null,
                     type: String? = null, visible: Boolean = true) =
        ProbeNode(index, parent, text, null, id, type, "", visible, false, false, false, false)

    @Test fun meituanRequiresOrderNumberInTheDetailContainer() {
        val nodes = listOf(
            node(0, -1),
            node(1, 0, id = "$MEITUAN_PACKAGE:id/ifs"),
            node(2, 1, id = "$MEITUAN_PACKAGE:id/r19"),
            node(3, 2, "订单已完成"),
            node(4, 1),
            node(5, 4, "订单信息"),
            node(6, 4),
            node(7, 6, "订单号码"),
            node(8, 6, "1234 5678 9012 3456"),
            node(9, 4),
            node(10, 9, "下单时间"),
            node(11, 9, "2026-01-02 12:34:56"),
        )
        val sample = ProbeSnapshot(42, MEITUAN_PACKAGE, MEITUAN_VERSION, nodes)
        val order = "1234567890123456"
        assertEquals(MeituanOrderFields("订单已完成", "2026-01-02 12:34:56", 42), sample.verifyMeituanOrder(order))
        assertNull(sample.verifyMeituanOrder("9876543210987654"))
        assertNull(sample.copy(nodes = nodes.map { if (it.index == 8) it.copy(parent = 0) else it }).verifyMeituanOrder(order))
        assertNull(sample.copy(nodes = nodes.map { if (it.index == 8) it.copy(visible = false) else it }).verifyMeituanOrder(order))
    }

    @Test fun jdReadsOnlySelectedVariantAndPriceInsideProductDetail() {
        val nodes = listOf(
            node(0, -1),
            node(1, 0, id = "com.jd.lib.productdetail.feature:id/a6c"),
            node(2, 1, "\u200b显示器 示例", type = "android.widget.TextView"),
            node(3, 1, "已选中款式 27英寸", type = "android.widget.Button"),
            node(4, 1),
            node(5, 4),
            node(6, 5),
            node(7, 6, "2999.99元，到手价", type = "android.view.ViewGroup"),
            node(8, 4, "满3000已减535"),
            node(9, 0, "1000元，到手价", type = "android.view.ViewGroup"),
        )
        val sample = ProbeSnapshot(42, JD_PACKAGE, JD_VERSION, nodes)
        val expected = JdProductFields("显示器 示例", "27英寸", "2999.99", "CNY", "到手价", "满3000已减535", 42)
        assertEquals(expected, sample.readJdProduct("显示器 示例", "27英寸"))
        assertNull(sample.readJdProduct("显示器 示例", "31.5英寸"))
        assertNull(sample.copy(appVersion = "0").readJdProduct("显示器 示例", "27英寸"))
        assertNull(sample.copy(nodes = nodes.map { if (it.index == 7) it.copy(visible = false) else it })
            .readJdProduct("显示器 示例", "27英寸"))
    }

    @Test fun replayPrivateSamplesWhenProvided() = runTest {
        val path = System.getenv("CARDDOCK_SAMPLE_DIR")
        assumeNotNull(path)
        val dir = File(path!!)
        val publicJdSample = File(dir, "carddock-jd-a-sku-link-response.html")
        if (publicJdSample.isFile) {
            assertEquals("JD_IDENTITY_CONFIRMED", JdShareVerifier().verifyPage(publicJdSample.readText()).outcome)
        }
        val publicJdB = File(dir, "jd-b-link-response.html")
        if (publicJdB.isFile) {
            val html = publicJdB.readText()
            assertEquals(JdProduct.B.sku, JdShareVerifier().verifyPage(html, JdProduct.B).jdFields?.sku)
            assertEquals("IDENTITY_MISMATCH", JdShareVerifier().verifyPage(html, JdProduct.A).outcome)
        }
        fun read(name: String) = File(dir, "$name.json").readText().removePrefix("\uFEFF")
        fun sample(name: String) = ruleJson.decodeFromString<ProbeSnapshot>(read(name))
        val target = ruleJson.parseToJsonElement(read("mt-o1-target")).jsonObject
        val order = target.getValue("orderNumber").jsonPrimitive.content.filterNot(Char::isWhitespace)
        val orderTime = target.getValue("orderTime").jsonPrimitive.content
        for (number in 1..5) {
            val suffix = number.toString().padStart(2, '0')
            assertNull(sample("mt-o1-$suffix").verifyMeituanOrder(order))
            assertTrue(sample("mt-o1-$suffix").hasCollapsedMeituanOrder())
            val product = sample("jd-a-clear-$suffix").readJdProduct("海信GX 27款", "GX 27款/27/黑曜屏/2304分区")
            assertEquals("3764", product?.amount)
            assertEquals("满3000已减535", product?.visibleDiscount)
            assertNull(sample("jd-a-$suffix").readJdProduct("海信GX 27款", "GX 27款/27/黑曜屏/2304分区"))
        }
        for (name in listOf("mt-o1-expanded", "mt-o1-route-reopen")) {
            val snapshot = sample(name)
            assertEquals("订单已完成", snapshot.verifyMeituanOrder(order)?.status)
            assertTrue("Order time must match the private target", snapshot.verifyMeituanOrder(order)?.orderTime == orderTime)
            assertNull(snapshot.verifyMeituanOrder("1234567890123456"))
        }
        assertNull(sample("jd-b-initial").readJdProduct("海信GX 27款", "GX 27款/27/黑曜屏/2304分区"))
        val bPrice = sample("jd-b-initial").readJdProduct(JdProduct.B.appTitlePrefix, JdProduct.B.variant)
        assertEquals("6498", bPrice?.amount)
        assertEquals("到手价，全网低价", bPrice?.priceLabel)
        val collapsed = sample("mt-o1-01")
        val expanded = sample("mt-o1-expanded")
        val home = collapsed.copy(nodes = listOf(node(0, -1), node(1, 0, "外卖", "$MEITUAN_PACKAGE:id/fqa"),
            node(2, 0, "我的")))
        var opened = false
        var clicked = false
        var homeReads = 0
        var retried = false
        val transientClicks = ArrayDeque(listOf(RuleClick.AMBIGUOUS, RuleClick.REJECTED))
        val phone = object : RulePhone {
            override fun installedVersion(packageName: String) = MEITUAN_VERSION
            override fun launch(target: RuleTarget, activity: String) = error("Unexpected Cainiao launch")
            override fun openMeituanOrder(order: MeituanOrderTarget) { opened = true }
            override suspend fun retryMeituanOrderFromHome(order: MeituanOrderTarget) {
                assertFalse(retried)
                assertEquals(8, homeReads)
                retried = true
            }
            override fun read(detailTarget: RuleTarget?): RulePage {
                assertTrue(opened)
                assertEquals(RuleTarget(MEITUAN_PACKAGE, MEITUAN_VERSION), detailTarget)
                if (!retried) {
                    homeReads++
                    return RulePage(MEITUAN_PACKAGE, emptySet(), false, 0, home)
                }
                return RulePage(MEITUAN_PACKAGE, emptySet(), false, 0, if (clicked) expanded else collapsed)
            }
            override fun click(selector: RuleSelector): RuleClick {
                assertFalse(clicked)
                assertEquals(RuleSelector("配送骑手/支付方式等"), selector)
                transientClicks.removeFirstOrNull()?.let { return it }
                clicked = true
                return RuleClick.ACCEPTED
            }
        }
        val template = ValidatedRule.parse(File("src/debug/assets/rules/meituan-order-v1.json").readText())
        val result = LocalRuleRunner(phone).execute(template, { _, _ -> }, {}, {},
            MeituanOrderTarget("1234567890123456789", order))
        assertTrue(clicked)
        assertEquals("ORDER_CONFIRMED", result.outcome)
        assertEquals("订单已完成", result.orderFields?.status)
    }
}
