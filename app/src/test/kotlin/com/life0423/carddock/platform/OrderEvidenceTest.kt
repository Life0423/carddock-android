package com.life0423.carddock.platform

import android.app.Application
import android.graphics.Rect
import com.life0423.carddock.core.port.DeviceResponse
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

    /** 合计只接受同一订单中带人民币标记的唯一金额，保留分与零值。 */
    @Test fun totalCarriesCurrencyAndRejectsConflictingReadings() {
        assertEquals(3150L, listOf("合计：￥31.50", "合计：¥31.5").orderTotalCents())
        assertEquals(0L, listOf("合计：¥0.00").orderTotalCents())
        assertNull(listOf("合计：31.50", "优惠：¥3.50", "合计：USD 3.50").orderTotalCents())
        assertNull(listOf("合计：¥12.34", "合计:USD 21.34").orderTotalCents())
        assertNull(listOf("合计：¥12.34", "合计：¥21.34").orderTotalCents())
    }

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

    @Test fun fullWidthListAllowsOnlyOnePixelOfBottomRounding() {
        val root = DetailNode(-1,null,null,"",true,Rect(0,0,1080,2700))
        val region = root.copy(parent=0,bounds=Rect(0,0,927,2313),drawingOrder=7)
        val active = root.copy(parent=2,bounds=Rect(0,237,927,2313))
        val bar = root.copy(parent=0,bounds=Rect(0,0,927,228),drawingOrder=8)
        val title = field("订单列表",170).copy(parent=4)
        fun accepts(bottom: Int, right: Int = 927): Pair<Boolean,Boolean> {
            val old = root.copy(parent=0,bounds=Rect(0,363,right,bottom),drawingOrder=6)
            val w = DetailWindow("sample",listOf(root,old,region,active,bar,title),false)
            val lists = listOf(old,active)
            assertNull(w.foregroundOrderNode(lists))
            return (w.foregroundOrderNode(lists, listViewports = true) === active) to w.ownsResultToolbar(title,active,lists,old.bounds)
        }
        assertEquals(true to true,accepts(2314))
        assertEquals(false to false,accepts(2315))
        assertEquals(false to false,accepts(2314,928))
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

    @Test fun receiptTargetGestureRequiresUncoveredRowInVisibleCollection() {
        val root = DetailNode(-1,null,null,"",true,Rect(0,0,300,600))
        val list = root.copy(parent=0,scrollable=true,drawingOrder=1)
        val date = field("10-22 12:35",150).copy(parent=1,drawingOrder=1)
        val overlay = root.copy(parent=0,drawingOrder=2)
        assertTrue(DetailWindow("sample",listOf(root,list,date),false).exposesReceiptTarget(date))
        assertFalse(DetailWindow("sample",listOf(root,list,date,overlay),false).exposesReceiptTarget(date))
        assertFalse(DetailWindow("sample",listOf(root,list.copy(scrollable=false),date),false).exposesReceiptTarget(date))
        assertFalse(DetailWindow("sample",listOf(root,list,date,date.copy()),false).exposesReceiptTarget(date))
    }

    @Test fun originalOrderLinkNeedsSameVisibleSuccessfulReceipt() {
        val root = DetailNode(-1,null,null,"",true,Rect(0,0,300,600))
        val link = field("查看原订单",300).copy(parent=0)
        val paid = field("支付成功",120).copy(parent=0)
        assertTrue(DetailWindow("sample",listOf(root,paid,link),false).exposesReceiptTarget(link))
        assertFalse(DetailWindow("sample",listOf(root,link),false).exposesReceiptTarget(link))
        assertFalse(DetailWindow("sample",listOf(root,paid,link,root.copy(parent=0,drawingOrder=3)),false).exposesReceiptTarget(link))
    }

    @Test fun missingDateInSummaryOpensFullLedgerBeforeUsingReceipt() = runTest {
        val order = OrderObservation("1000001", LocalDateTime.parse("2026-10-22T12:34:56"), true, true, null)
        val pages = listOf(
            listOf("我的账单"),
            listOf("账单","10-29 12:35","查看全部账单"),
            listOf("账单","查看全部账单","10-22 12:35"),
            listOf("账单","筛选","10-22 12:35"),
            listOf("-22.90","支付成功","查看原订单"),
            listOf("订单已完成","订单号：1000001","下单时间：2026-10-22 12:34:56")
        )
        val root = DetailNode(-1,null,null,"",true,Rect(0,0,300,600))
        var screen = 0
        var lastObserved = -1
        val taps = mutableListOf<String>()
        val paid = LinkedOrderReceipt(order,2290).read({ true }, {
            val index = screen
            lastObserved = index
            if (screen == 2) screen++ // 模拟跳转后旧摘要短暂滞留。
            DetailWindow("sample",listOf(root) + pages[index].mapIndexed { i,s -> field(s,40*i).copy(parent=0) },false)
        }, tap = {
            if (it.label == "10-22 12:35") assertEquals(3,lastObserved)
            taps += it.label
            screen++
            true
        }, scroll = { error("已有完整流水入口") })
        assertEquals(2290L,paid)
        assertEquals(listOf("我的账单","查看全部账单","10-22 12:35","查看原订单"),taps)
    }

    @Test fun verifiedReceiptAloneCannotProveMonthlyCoverage() = runTest {
        val root = DetailNode(-1,null,null,"",true,Rect(0,0,300,600))
        val cutoff = java.time.ZonedDateTime.parse("2026-03-30T15:00:00+08:00")
        val marker = "下单时间：2026-03-12"
        val detail = listOf("订单已完成","订单类型：外卖","订单号：1000001","下单时间：2026-03-12 09:00:00","合计：￥18.90")
        suspend fun trial(reportedCount: Int?, withdrawCount: Boolean = false, totalLabel: String? = null, grouped: Boolean = false, expectsDetail: Boolean = reportedCount != null): DeviceResponse {
            val scan = OrderCollection("sample",cutoff,emptyList())
            val pane = root.copy(parent=0,bounds=Rect(0,160,300,600),scrollable=true,collectionRows=reportedCount)
            val list = DetailWindow("sample",listOf(root,field("全部订单",0).copy(parent=0),
                field("全部时间",40,true).copy(parent=0),field("外卖",80,true).copy(parent=0),pane,
                field(marker,180).copy(parent=4),field("已完成",240).copy(parent=4),
                field("已显示全部订单",360).copy(parent=4)) +
                (if (grouped) listOf(root.copy(parent=4,rowIndex=0,bounds=Rect(0,370,300,450))) else emptyList()) +
                listOfNotNull(totalLabel?.let { field(it,390).copy(parent=if (grouped) 8 else 4) }),false)
            fun page(labels: List<String>) = DetailWindow("sample",listOf(root) +
                labels.mapIndexed { i,s -> field(s,40*i).copy(parent=0) },false)
            var view = list
            var firstObservation = true
            val observe = {
                if (firstObservation) {
                    firstObservation = false
                    list.copy(nodes = list.nodes.map { n ->
                        if (n.label == marker) n.copy(bounds = Rect(10,170,240,196)) else n
                    })
                } else view
            }
            val open: (DetailNode) -> Boolean = { n ->
                if (n.label == marker) assertEquals(180,n.bounds!!.top)
                view = when (n.label) {
                    marker, "查看原订单" -> page(detail)
                    "我的账单" -> page(listOf("全部账单","03-12 09:01"))
                    "03-12 09:01" -> page(listOf("-18.90","支付成功","查看原订单"))
                    else -> error("未知测试入口")
                }
                true
            }
            val first = scan.runWindow({ true },observe,open,{ false },null)
            if (!expectsDetail) {
                assertSame(list,view) // 无完整性依据时，不打开任何订单或账单。
                return first
            }
            assertSame(nextOrderWindow,first)
            view = page(listOf("我的账单"))
            assertSame(nextOrderWindow,scan.runWindow({ true },{ view },open,{ false },null))
            assertEquals(1,scan.reconciledCount)
            view = if (withdrawCount) list.copy(nodes=list.nodes.map { if (it === pane) it.copy(collectionRows=null) else it }) else list
            return scan.runWindow({ true },{ view },open,{ false },null)
        }
        val full = trial(1) as DeviceResponse.Ready
        assertEquals("18.90",full.snapshot.fields["paid"])
        assertTrue(trial(null) is DeviceResponse.NeedsUser)
        assertTrue(trial(2) is DeviceResponse.NeedsUser)
        assertTrue(trial(1,withdrawCount=true) is DeviceResponse.NeedsUser)
        // 同一试查入口验证显式总数、页内小计以及与集合元数据冲突三种来源。
        assertTrue(trial(null, totalLabel="订单总数：1笔", expectsDetail=true) is DeviceResponse.Ready)
        assertTrue(trial(null, totalLabel="订单总数：1笔", grouped=true) is DeviceResponse.NeedsUser)
        assertTrue(trial(null, totalLabel="本页订单总数：1笔") is DeviceResponse.NeedsUser)
        assertTrue(trial(1, totalLabel="订单总数：2笔", expectsDetail=false) is DeviceResponse.NeedsUser)
    }

    @Test fun countOnlyOnLastPageMustBeReadAgainDuringReconciliation() = runTest {
        val root = DetailNode(-1,null,null,"",true,Rect(0,0,300,600))
        val markers = listOf("下单时间：2026-03-12","下单时间：2026-03-11","下单时间：2026-03-10")
        fun listPage(index: Int, count: Int?, firstRow: Int) = DetailWindow("sample", listOf(root,
            field("全部订单",0).copy(parent=0), field("全部时间",40,true).copy(parent=0),
            field("外卖",80,true).copy(parent=0), root.copy(parent=0,scrollable=true,
                bounds=Rect(0,160,300,600),canGoBack=index==1,canGoForward=index==0,collectionRows=count),
            field(markers[firstRow],180).copy(parent=4), field("已完成",220).copy(parent=4),
            field(markers[firstRow+1],270).copy(parent=4), field("已完成",310).copy(parent=4)) +
            if (index==1) listOf(field("已显示全部订单",360).copy(parent=4)) else emptyList(),false)
        suspend fun trial(finalCount: Int?, pageStarts: List<Int> = listOf(0,1)): DeviceResponse {
            val scan = OrderCollection("sample", java.time.ZonedDateTime.parse("2026-03-30T15:00:00+08:00"), emptyList())
            var surveyPage = 0
            val sourceCount = pageStarts.last() + 2
            assertSame(nextOrderWindow, scan.runWindow({ true }, {
                listPage(surveyPage, if (surveyPage == 1) sourceCount else null, pageStarts[surveyPage])
            },
                { error("列表预查不应打开订单详情") }, { surveyPage++; true },null))
            // 正式读取两页。末页的总数必须再次读到，相邻页面重叠的订单也只能计入一次。
            val opened = mutableListOf<Int>()
            suspend fun replay(): DeviceResponse {
                var index = 0
                var detail: Int? = null
                return scan.runWindow({ true }, {
                    detail?.let { item -> DetailWindow("sample",listOf(root) +
                        listOf("订单已完成","订单类型：外卖","订单号：100000${item + 1}",
                            markers[item] + " 09:00:00","原实付：￥1.00")
                            .mapIndexed { i,v -> field(v,40*i).copy(parent=0) },false)
                    } ?: listPage(index,if (index==1) finalCount else null, pageStarts[index])
                }, { node -> detail = markers.indexOf(node.label).also { opened += it }; true },
                    { index++; true },null)
            }
            repeat(4) { assertSame(nextOrderWindow,replay()) }
            assertEquals(pageStarts.flatMap { listOf(it,it+1) },opened)
            return replay().also { if (finalCount == null) assertNull(scan.digest) }
        }
        val stuck = OrderCollection("sample",java.time.ZonedDateTime.parse("2026-03-30T15:00:00+08:00"),emptyList())
        var scrolls = 0
        val unchanged = stuck.runWindow({ true }, { listPage(0,null,0) },
            { error("滚动未生效，不应读取详情") }, { scrolls++; true },null)
        assertTrue((unchanged as DeviceResponse.NeedsUser).message.contains("仍显示同一组记录"))
        assertEquals(1,scrolls)
        val delayed = OrderCollection("sample",java.time.ZonedDateTime.parse("2026-03-30T15:00:00+08:00"),emptyList())
        var loading = false
        val timeout = delayed.runWindow({ true }, { if (loading) null else listPage(0,null,0) },
            { error("列表未加载，不应读取详情") }, { loading = true; true },null)
        assertTrue((timeout as DeviceResponse.NeedsUser).message.contains("已检查到第 1 屏"))
        val full = trial(3) as DeviceResponse.Ready
        assertEquals("3.00",full.snapshot.fields["paid"])
        assertEquals("3",full.snapshot.fields["orders"])
        assertTrue((trial(null) as DeviceResponse.NeedsUser).message.contains("没有足够的总笔数"))
        val footerOnly = trial(2, pageStarts=listOf(0,0)) as DeviceResponse.Ready
        assertEquals("2.00",footerOnly.snapshot.fields["paid"])
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
