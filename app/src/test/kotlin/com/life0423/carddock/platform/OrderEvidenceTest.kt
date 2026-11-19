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

    @Test fun filterMustSurviveReopeningAndPixelsRequireConsent() = runTest {
        suspend fun trial(exposesSelection: Boolean, retains: Boolean): String? {
            val selected = mutableSetOf<String>()
            var opened = false
            val sheet = listOf("清空", "近3个月", "外卖", "确认")
            return applyOrderFilters(observe = {
                val names = if (opened) sheet else listOf("筛选")
                DetailWindow("sample", names.mapIndexed { i, s -> field(s, i*32, exposesSelection && s in selected) }, false)
            }, tap = {
                when (it.label) {
                    "清空" -> selected.clear()
                    "确认" -> { opened = false; if (!retains) selected.clear() }
                    "筛选" -> opened = true
                    else -> selected.add(it.label)
                }
                true
            }, sample = null, panelVisible = { assertFalse(it) })
        }
        assertNull(trial(exposesSelection = true, retains = true))
        assertEquals(FILTER_APPEARANCE_REQUIRED, trial(exposesSelection = false, retains = true))
        assertNotNull(trial(exposesSelection = true, retains = false))
    }

    @Test fun paidReceiptMustLinkToSameOrderAndSecond() = runTest {
        val order = OrderObservation("1000001", LocalDateTime.parse("2026-10-22T12:34:56"), true, true, null)
        suspend fun read(linkedId: String, linkedTime: String, debit: String): Long? {
            var screen = 0
            val pages = listOf(
                listOf(field("我的账单",0)),
                listOf(field("全部账单",0),field("10-22 12:35",40)),
                listOf(field(debit,0),field("支付成功",40),field("查看原订单",100)),
                listOf(field("订单已完成",0),field("订单号：" + linkedId,40),field("下单时间：" + linkedTime,80))
            )
            return LinkedOrderReceipt(order,2290).read({ true }, { DetailWindow("sample", pages[screen],false) },
                tap = { screen++; true }, scroll = { false })
        }
        assertEquals(2290L, read("1000001","2026-10-22 12:34:56","-22.90"))
        assertNull(read("1000002","2026-10-22 12:34:56","-22.90"))
        assertNull(read("1000001","2026-10-22 12:34:55","-22.90"))
        assertNull(read("1000001","2026-10-22 12:34:56","-2.90"))
    }
}
