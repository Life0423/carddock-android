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
