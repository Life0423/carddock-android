package com.wangzan101.mika.core.verify

import com.wangzan101.mika.core.agent.DockCardIntent
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import org.junit.Assert.*
import org.junit.Test

class CardPriceVerifierTest {
    private val product = "示例耳机 标准款"
    private val key = PriceKey("shop", product, "标准款", "标价")
    private fun screen(vararg labels: String) = ScreenSnapshot("s", "shop",
        (listOf(product, "已选：标准款", "加入购物车") + labels).mapIndexed { i, n ->
            UiElement("e$i", "text", n, 0, 0, listOf(0, i * 10, 250, i * 10 + 9), null, false, false, false)
        }, 1000, 1000)

    @Test fun comparablePriceShowsChangeAgainstImmediatelyPreviousCheck() {
        val old = CardPrice(PriceKey("shop", "item", "blue", "标价"), 1015)
        assertEquals("比上次降 ¥0.16", old.copy(low = 999).changeFrom(old))
        assertEquals("与上次持平", old.changeFrom(old))
        assertEquals("上次 ¥10.15", old.copy(high = 2000).changeFrom(old))
        assertNull(old.copy(key = old.key.copy(variant = "red")).changeFrom(old))
    }

    @Test fun floatingBadgeCanCoverOnlyItsOwnBounds() {
        val base = screen("标价 ¥15")
        fun window(layer: Int, nodes: List<com.wangzan101.mika.core.uistate.UiTreeNode>) =
            com.wangzan101.mika.core.uistate.UiWindowTree(layer,"application",layer,null,"shop",true,true,nodes)
        val detail = window(1, base.elements.map {
            com.wangzan101.mika.core.uistate.UiTreeNode(it.role, it.bounds, ref = it.ref)
        })
        fun read(box: List<Int>) = CardPrice.read(base.copy(windows = listOf(detail, window(2, listOf(
            com.wangzan101.mika.core.uistate.UiTreeNode("badge", box))))), "", key)
        assertEquals(1500L, read(listOf(800, 500, 950, 700))?.low)
        assertNull(read(listOf(0, 0, 999, 999)))
        assertNull(read(listOf(0, 30, 250, 40)))
    }

    @Test fun hostUnderstandsCouponLabelWithoutGenericWordPrice() {
        val kind = com.wangzan101.mika.core.agent.DockCardIntent
        assertEquals("看看未交作业", com.wangzan101.mika.core.agent.DockCardIntent.Query.currentAction("以后我点这张卡时，看看未交作业"))
        assertEquals("在学习通打开这张卡", com.wangzan101.mika.core.agent.DockCardIntent.Query.currentAction("在学习通打开这张卡"))
        assertEquals("现在核对商品价格", com.wangzan101.mika.core.agent.DockCardIntent.DailyPrice.currentAction("每天跟踪商品价格"))
        assertEquals(com.wangzan101.mika.core.agent.DockCardIntent.DailyPrice, kind.interpret("每天跟踪京东这个规格的国补领后价，更新这张卡"))
        assertEquals(com.wangzan101.mika.core.agent.DockCardIntent.Price, kind.interpret("点卡看看这款的券后价"))
    }

    @Test fun comparesSameVariantAndReadsDecimalsAndRanges() {
        assertEquals("¥19.99", CardPrice.read(screen("标价 ¥19.99", "会员价 ¥15"), "", key)?.display)
        assertEquals("¥10–¥15", CardPrice.read(screen("标价￥10～15"), "", key)?.display)
        assertEquals("¥10起", CardPrice.read(screen("标价￥10起"), "", key)?.display)
        assertNull(CardPrice.read(screen("标价 ¥19.99", "标价 ¥29.99"), "", key))
        assertNull(CardPrice.read(screen("标价 ¥19.99"), "", key.copy(variant = "白色")))
        assertNull(CardPrice.read(screen("¥9.99", "会员价 ¥6.88"), "", key.copy(label = "页面标价")))
    }

    @Test fun labelledYuanAfterPriceAndSelectedQuantityMatchTheSameSku() {
        val page = screen("1280.95元，国补领后价", "¥1549").let { s -> s.copy(elements = s.elements.map {
            if (it.name.startsWith("已选")) it.copy(name = "已选：白色，公开版，1件") else it
        }) }
        val proposal = "商品：示例耳机 标准款\n规格：白色，公开版\n价格标签：国补领后价"
        val quote = requireNotNull(CardPrice.read(page, proposal, null))
        assertEquals(128095L, quote.low)
        assertEquals("白色，公开版", quote.key.variant)
        assertNull(CardPrice.read(page, "", quote.key.copy(variant = "标准款")))
    }

    @Test fun cardTriggerAndActionStaySeparate() {
        assertEquals(DockCardIntent.PickupPage, DockCardIntent.interpret("点这张卡时给我看取件码"))
        assertEquals(DockCardIntent.PickupPage, DockCardIntent.interpret("点击卡片打开菜鸟待取件页"))
        assertEquals(DockCardIntent.IdentityPage, DockCardIntent.interpret("打开淘宝中相应的菜鸟驿站中的身份码"))
        assertEquals(DockCardIntent.IdentityDisplay, DockCardIntent.interpret("打开淘宝，并核实身份码是否展示"))
        assertEquals(DockCardIntent.DailyPrice, DockCardIntent.interpret("每天跟踪这款耳机的价格"))
        assertEquals(DockCardIntent.Query, DockCardIntent.interpret("查询京东筛选里的价格分段"))
    }
}
