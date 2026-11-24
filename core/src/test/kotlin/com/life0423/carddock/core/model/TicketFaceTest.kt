package com.life0423.carddock.core.model

import com.life0423.carddock.core.rule.RuleSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class TicketFaceTest {
    private val rule = RuleSpec(id = "read", sourceId = "source", targetKey = "detail", steps = emptyList())

    /** 同一个来源依据数据变换票面，自动跳过标题，用户自定义的标题别名不会改变字段含义。 */
    @Test fun automaticAndExplicitFocusUseVerifiedFields() {
        val title = FieldValue("name", "名称", "分类 A")
        for ((field, role) in listOf(
            FieldValue("quantity", "数量", "0") to TicketRole.COUNT,
            FieldValue("orderTime", "下单时间", "2026-11-21 13:40:09") to TicketRole.TIME,
            FieldValue("status", "订单状态", "已完成") to TicketRole.STATUS,
            FieldValue("paid", "原实付（元）", "21.50") to TicketRole.AMOUNT,
            FieldValue("reading", "读数", "€12.00") to TicketRole.AMOUNT,
            FieldValue("reading", "读数", "8项") to TicketRole.COUNT,
            FieldValue("reading", "读数", "16:23") to TicketRole.TIME,
        )) {
            val snapshot = CardSnapshot(listOf(title, field), 123)
            assertEquals(role, snapshot.ticketFace(emptyList(), rule).role)
            assertEquals(field, snapshot.ticketFace(emptyList(), rule).fields.first())
            val renamed = snapshot.ticketFace(listOf(DisplayField(field.key, "重点")), rule)
            assertEquals(role, renamed.role)
            assertEquals(listOf(field.copy(label = "重点")), renamed.fields)
            assertEquals(TicketRole.TEXT, snapshot.ticketFace(listOf(DisplayField("name", "金额")), rule).role)
        }
        val sku = FieldValue("sku", "SKU", "100305104289")
        assertEquals(TicketRole.TEXT, CardSnapshot(listOf(sku), 123).ticketFace(emptyList(), rule).role)
    }
}
