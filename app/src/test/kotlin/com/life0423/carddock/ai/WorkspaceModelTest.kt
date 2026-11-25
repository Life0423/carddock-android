package com.life0423.carddock.ai

import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.model.CardSnapshot
import com.life0423.carddock.core.model.FieldValue
import com.life0423.carddock.core.model.TemplateKind
import com.life0423.carddock.core.rule.RuleSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspaceModelTest {
    @Test
    fun groupReplyMustMapToDistinctCardsInTheProvidedScope() {
        val scope = listOf("first", "second", "third")
        assertEquals(
            WorkspaceProposal("换机备选", listOf("second", "first")),
            decodeWorkspaceProposal("""{"name":" 换机备选 ","cards":[2,1]}""", scope),
        )
        assertNull(decodeWorkspaceProposal("""{"name":"候选","cards":[2,2]}""", scope))
        assertNull(decodeWorkspaceProposal("""{"name":"候选","cards":[1,9]}""", scope))
        assertNull(decodeWorkspaceProposal("""{"name":"候选","cards":[1]}""", scope))
    }

    @Test
    fun comparisonReadsLocalSnapshotsOnlyAndLeavesMissingFieldsEmpty() {
        val delivery = withField("delivery", FieldValue("status", "配送状态", "已完成"))
        val parcels = withField("parcels", FieldValue("status", "待取状态", "暂无待取"))
        val metrics = comparisonMetrics(listOf(delivery, parcels))
        assertEquals(listOf("配送状态", "待取状态"), metrics.map { it.label })
        assertEquals("暂无待取", metrics[1].value(parcels))
        assertNull(metrics[1].value(delivery))
        assertNull(metrics[1].value(withField("blank", FieldValue("status", "待取状态", ""))))
        assertEquals(listOf(metrics[1].id, metrics[0].id), decodeMetricSelection("""{"metrics":[2,9,1,2]}""", metrics))
    }

    @Test
    fun contextHidesOrderNumbersRoutesAndLinks() {
        assertEquals(
            "订单 （已隐藏） 路由 （已隐藏） 链接 （已隐藏）",
            modelText("订单 12345678901234567 路由 1234567890123456789 链接 https://example.com/order/123"),
        )
        assertEquals("小米平板 6 128GB", modelText("小米平板 6 128GB"))
        assertEquals("订单尾号：（已隐藏）", modelText("订单尾号：1234"))
        assertEquals("取件码 （已隐藏） 或取件码：（已隐藏）", modelText("取件码 8-26-1088 或取件码：A3-6281"))
    }

    private fun withField(id: String, field: FieldValue) = CardRecord(
        id = id, title = id, kind = TemplateKind.DELIVERY,
        rule = RuleSpec(id = id, sourceId = "demo", targetKey = id, steps = emptyList()),
        snapshot = CardSnapshot(listOf(field), fetchedAt = 0L),
    )
}
