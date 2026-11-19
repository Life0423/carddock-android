package com.life0423.carddock.platform

import com.life0423.carddock.core.port.DeviceResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** 用与真机不同的标签及结构验证归属和重放，避免将相邻分类的数值写入卡片。 */
class VisibleCountTest {
    private fun row(vararg labels: String): DetailWindow {
        val root = DetailNode(-1, "row", "android.view.ViewGroup", "", true)
        return DetailWindow("sample", listOf(root) + labels.mapIndexed { i, value ->
            DetailNode(0, "sample:id/field_$i", "android.widget.TextView", value, true)
        }, false)
    }

    @Test fun learnsFieldAndReplaysChangedValue() = runTest {
        var saved: CountField? = null
        val first = readVisibleCount("sample", null, "书籍", null, { true }, { row("书籍","4 项") }, { _, _ -> false }) { fields, structure ->
            assertFalse("书籍" in structure || "4 项" in structure)
            fields.single().also { saved = it }
        }
        assertTrue(first is DeviceResponse.Ready)
        val next = readVisibleCount("sample", null, "书籍", saved!!.route, { true }, { row("书籍","5 项") }, { _, _ -> false }, null)
        assertEquals("5 项", (next as DeviceResponse.Ready).snapshot.fields["quantity"])
    }

    /** 多行复用同一套控件，示范须锁定完整名称。另一行数值变化不能改变选择。 */
    @Test fun partialLabelsDemonstrateOnceThenReplayOnlyTheChosenRow() = runTest {
        fun screen(first: String, second: String) = DetailWindow("sample", listOf(
            DetailNode(-1,"row","group","",true),
            DetailNode(0,"label","android.widget.TextView","寄出件",true),
            DetailNode(0,"value","android.widget.TextView",first,true),
            DetailNode(-1,"row","group","",true),
            DetailNode(3,"label","android.widget.TextView","签收件",true),
            DetailNode(3,"value","android.widget.TextView",second,true),
            DetailNode(-1,"toolbar","group","",true),
            DetailNode(6,"page_title","android.widget.TextView","签收件",true),
        ),false)
        var options = emptyList<CountField>()
        readVisibleCount("sample",null,"件",null,{true},{screen("5 项","5 项")},{ _, _ -> false }) { fields, structure ->
            assertFalse("签收件" in structure || "5 项" in structure)
            options = fields
            null
        }
        assertEquals(listOf("寄出件","签收件"),options.map { it.route.subject })
        val saved = options.last().route
        val result = readVisibleCount("sample",null,saved.subject,saved,{true},{screen("6 项","7 项")},{ _, _ -> false },null)
        assertEquals("7 项",(result as DeviceResponse.Ready).snapshot.fields["quantity"])
    }

    @Test fun foreignLabelStopsAssociation() = runTest {
        val result = readVisibleCount("sample", null, "书籍", null, { true }, { row("书籍","报刊","12 项") },
            { _, _ -> false }) { _, _ -> error("不能将报刊数量归给书籍") }
        assertTrue(result is DeviceResponse.NeedsUser)
    }

    @Test fun ordinaryTextInsideNavigationHostIsNeverTapped() = runTest {
        val page = row("目录","书籍","4 项")
        val nodes = listOf(page.nodes.first().copy(id = "sample:id/navigation_host")) + page.nodes.drop(1)
        val result = readVisibleCount("sample","目录","书籍",null,{ true },{ page.copy(nodes = nodes) },
            { _, _ -> error("不能点击内容区的普通文本") },null)
        assertTrue(result is DeviceResponse.NeedsUser)
    }
}
