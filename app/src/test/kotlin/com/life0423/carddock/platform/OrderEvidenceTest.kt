package com.life0423.carddock.platform

import android.app.Application
import android.graphics.Rect
import com.life0423.carddock.core.engine.OrderObservation
import java.time.LocalDateTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class OrderEvidenceTest {
    private fun field(label: String, top: Int, selected: Boolean = false) =
        DetailNode(-1, null, "android.widget.TextView", label, true, Rect(10,top,240,top+26), selected = selected)

    @Test fun foregroundListMayStartBelowBackgroundList() {
        val screen = Rect(0, 0, 600, 1000)
        fun area(parent: Int, bounds: Rect, z: Int) = DetailNode(parent, null, null, "", true, bounds, drawingOrder = z)
        val old = area(1, Rect(0,100,600,1000), 1)
        val active = area(3, Rect(0,150,600,960), 1)
        fun window(top: Rect, z: Int) = DetailWindow("sample", listOf(
            area(-1,screen,1), area(0,screen,2), old, area(0,top,z), active
        ), false)
        assertSame(active, window(screen,3).foregroundOrderNode(listOf(old,active)))
        assertNull(window(screen,2).foregroundOrderNode(listOf(old,active)))
        assertNull(window(Rect(0,110,600,1000),3).foregroundOrderNode(listOf(old,active)))
    }

    @Test fun stickyToolbarBindsOnlyAboveNewResultAndKnownOriginalList() {
        val root = DetailNode(-1,null,null,"",true,Rect(0,0,400,800))
        val old = root.copy(parent=0,bounds=Rect(0,170,400,800),drawingOrder=3)
        val body = root.copy(parent=0,drawingOrder=4)
        val result = root.copy(parent=2,bounds=Rect(0,125,400,800))
        val bar = root.copy(parent=0,bounds=Rect(0,0,400,120),drawingOrder=5)
        val title = field("订单列表",40).copy(parent=4)
        fun binds(toolbar: DetailNode, previous: Rect?) =
            DetailWindow("sample",listOf(root,old,body,result,toolbar,title),false)
                .ownsResultToolbar(title,result,listOf(old,result),previous)
        assertTrue(binds(bar,old.bounds))
        assertFalse(binds(bar,null))
        assertFalse(binds(bar,result.bounds))
        assertFalse(binds(bar.copy(drawingOrder=2),old.bounds))
        assertFalse(binds(bar.copy(bounds=Rect(0,0,400,800)),old.bounds))
    }

    @Test fun filterMustSurviveReopeningAndPixelsRequireConsent() = runTest {
        suspend fun trial(exposesSelection: Boolean, retains: Boolean, footerOrder: Int = 2,
                          taps: MutableList<String> = mutableListOf()): String? {
            val selected = mutableSetOf<String>()
            var opened = false
            return applyOrderFilters(observe = {
                val root = DetailNode(-1,null,null,"",true,Rect(0,0,300,600))
                val nodes = if (opened) listOf(root,root.copy(parent=0,drawingOrder=1),
                    field("订单筛选",20).copy(parent=1),
                    field("近3个月",100,exposesSelection && "近3个月" in selected).copy(parent=1),
                    field("外卖",185,exposesSelection && "外卖" in selected).copy(parent=1),
                    root.copy(parent=0,bounds=Rect(0,480,300,600),drawingOrder=footerOrder),
                    field("清空",505).copy(parent=5),field("确认",550).copy(parent=5))
                else listOf(root,field("订单列表",0).copy(parent=0), field("筛选",40).copy(parent=0))
                DetailWindow("sample",nodes,false)
            }, tap = {
                taps += it.label
                when (it.label) {
                    "清空" -> selected.clear()
                    "确认" -> { opened = false; if (!retains) selected.clear() }
                    "筛选" -> opened = true
                    else -> selected.add(it.label)
                }
                true
            }, sample = null, panelVisible = { assertFalse(it) },
                resultFilterButton = { w -> w.nodes.singleOrNull { it.label == "筛选" } })
        }
        val taps = mutableListOf<String>()
        assertNotNull(trial(true,true,footerOrder=0,taps=taps))
        assertEquals(listOf("筛选"), taps)
        assertNull(trial(exposesSelection = true, retains = true))
        assertEquals(FILTER_APPEARANCE_REQUIRED, trial(exposesSelection = false, retains = true))
        assertNotNull(trial(exposesSelection = true, retains = false))
    }

    @Test fun filterFieldsInDifferentStackedPagesCannotBeCombined() = runTest {
        val root = DetailNode(-1,null,null,"",true,Rect(0,0,300,600))
        val old = root.copy(parent=0,drawingOrder=1)
        val overlay = root.copy(parent=0,drawingOrder=2)
        val hidden = listOf(field("外卖",100),field("清空",150),field("确认",200)).map { it.copy(parent=1) }
        val mixed = DetailWindow("sample", listOf(root,old) + hidden + overlay +
            listOf(field("订单筛选",10),field("近3个月",50)).map { it.copy(parent=5) },false)
        var opened = false
        val taps = mutableListOf<String>()
        val result = applyOrderFilters(observe = {
            if (opened) mixed else DetailWindow("sample",listOf(root,field("订单列表",0).copy(parent=0),field("筛选",40).copy(parent=0)),false)
        }, tap = { taps += it.label; opened = true; true }, sample = null, panelVisible = {},
            resultFilterButton = { it.nodes.singleOrNull { n -> n.label == "筛选" } })
        assertNotNull(result)
        assertEquals(listOf("筛选"),taps)
    }

    @Test fun billDateGestureRequiresUncoveredRowInVisibleCollection() {
        val root = DetailNode(-1,null,null,"",true,Rect(0,0,300,600))
        val list = root.copy(parent=0,scrollable=true,drawingOrder=1)
        val date = field("10-22 12:35",150).copy(parent=1,drawingOrder=1)
        val overlay = root.copy(parent=0,drawingOrder=2)
        assertTrue(DetailWindow("sample",listOf(root,list,date),false).exposesBillDate(date))
        assertFalse(DetailWindow("sample",listOf(root,list,date,overlay),false).exposesBillDate(date))
        assertFalse(DetailWindow("sample",listOf(root,list.copy(scrollable=false),date),false).exposesBillDate(date))
        assertFalse(DetailWindow("sample",listOf(root,list,date,date.copy()),false).exposesBillDate(date))
    }

    @Test fun paidReceiptMustLinkToSameOrderAndSecond() = runTest {
        val order = OrderObservation("1000001", LocalDateTime.parse("2026-10-22T12:34:56"), true, true, null)
        suspend fun read(linkedId: String, linkedTime: String, debit: String, splitReceipt: Boolean = false): Long? {
            var screen = 0
            val pages = listOf(
                listOf(field("我的账单",0)),
                listOf(field("全部账单",0),field("10-22 12:35",40)),
                listOf(field(debit,0),field("支付成功",40),field("查看原订单",100)),
                listOf(field("订单已完成",0),field("订单号：" + linkedId,40),field("下单时间：" + linkedTime,80))
            )
            val root = DetailNode(-1,null,null,"",true,Rect(0,0,300,600))
            return LinkedOrderReceipt(order,2290).read({ true }, {
                val nodes = if (screen == 2 && splitReceipt) listOf(root,root.copy(parent=0,drawingOrder=1),
                    pages[2][0].copy(parent=1), pages[2][1].copy(parent=1),
                    root.copy(parent=0,drawingOrder=2), pages[2][2].copy(parent=4))
                else listOf(root) + pages[screen].map { it.copy(parent=0) }
                DetailWindow("sample",nodes,false)
            },
                tap = { screen++; true }, scroll = { false })
        }
        assertEquals(2290L, read("1000001","2026-10-22 12:34:56","-22.90"))
        assertNull(read("1000001","2026-10-22 12:34:56","-22.90",splitReceipt = true))
        assertNull(read("1000002","2026-10-22 12:34:56","-22.90"))
        assertNull(read("1000001","2026-10-22 12:34:55","-22.90"))
        assertNull(read("1000001","2026-10-22 12:34:56","-2.90"))
    }
}
