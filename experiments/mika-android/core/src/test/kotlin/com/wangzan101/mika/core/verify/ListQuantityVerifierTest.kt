package com.wangzan101.mika.core.verify

import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListQuantityVerifierTest {
    private fun screen(vararg items: Pair<String, String>): ScreenSnapshot {
        val nodes = items.flatMapIndexed { i, (title, status) ->
            val top = i * 50
            fun element(name: String, left: Int, right: Int) = UiElement("e$i/$left", "text", name, 0, top,
                listOf(left, top, right, top + 48), null, false, false, false)
            listOf(element("$title $status", 0, 999), element(title, 10, 490), element(status, 510, 900))
        }
        return ScreenSnapshot("s1", "example.app", nodes, 1000, 1000)
    }
    @Test fun countsFromRowsAndLeavesItemNumbersAndTimesAlone() {
        val rows = screen("第4次实验" to "未交", "甲" to "待批阅", "乙" to "待批阅", "丙" to "待批阅")
        val draft = "列表共 3 项。未交（1项）：第4次实验，剩余14小时。其余 2 项状态均为「待批阅」：甲、乙、丙。"
        val checked = requireNotNull(ListQuantityCheck.check(draft, rows))
        assertEquals(draft.replace("共 3", "共 4").replace("其余 2", "其余 3"), checked.answer)
        assertEquals(2, checked.corrected)
    }
    @Test fun otherAppStatusesUseTheSameRowsAndIgnoreDuplicateDescriptions() {
        val snap = screen("蓝色水杯" to "已发货", "桌面灯" to "已发货", "笔记本" to "备货中")
        val rows = snap.copy(elements = snap.elements + snap.elements.first().copy(ref = "extra"))
        val draft = "当前列表共2件；已发货1件：蓝色水杯、桌面灯；备货中1件：笔记本"
        assertEquals(draft.replace("共2", "共3").replace("已发货1", "已发货2"),
            ListQuantityCheck.check(draft, rows)?.answer)
    }
    @Test fun checksParallelCountsAndScreenTotalInTheDisplayedAnswer() {
        val snap = screen("甲单" to "未完成", "乙单" to "完成", "丙单" to "完成")
        val draft = "本屏共显示 **2** 项订单：「未完成」2 项、「完成」1 项。未完成：甲单。完成：乙单、丙单。"
        val checked = requireNotNull(ListQuantityCheck.check(draft, snap))
        assertEquals("本屏共显示 **3** 项订单：「未完成」1 项、「完成」2 项。未完成：甲单。完成：乙单、丙单。", checked.answer)
        assertEquals(3, checked.checked)
    }
    @Test fun estimatesAndOtherPagesDoNotShrinkToThisScreen() {
        val snap = screen("甲" to "完成", "乙" to "完成")
        assertNull(ListQuantityCheck.check("上一页和这一页列表共 20 项。甲、乙只是其中两项", snap))
        assertNull(ListQuantityCheck.check("本页共 1 项，甲、乙", snap.copy(protected = true)))
    }
}
