package com.wangzan101.mika.experiment

import kotlinx.serialization.Serializable

internal const val MEITUAN_PACKAGE = "com.sankuai.meituan"
internal const val JD_PACKAGE = "com.jingdong.app.mall"
internal const val MEITUAN_VERSION = "12.68.402"
internal const val JD_VERSION = "16.1.50"

private val orderNumberPattern = Regex("[0-9]{15,30}")

@Serializable
internal class MeituanOrderTarget(val oid: String, val orderNumber: String) {
    init {
        require(oid.matches(Regex("[0-9]{19}")) && orderNumber.matches(orderNumberPattern)) {
            "请输入 19 位路由 oid 和完整订单号码。"
        }
    }
}

@Serializable
internal data class MeituanOrderFields(val status: String, val orderTime: String?, val verifiedAt: Long)

@Serializable
internal data class JdProductFields(
    val title: String,
    val variant: String,
    val amount: String,
    val currency: String,
    val priceLabel: String,
    val visibleDiscount: String?,
    val verifiedAt: Long,
)

private val formatCharacters = Regex("\\p{Cf}")
private val orderTimePattern = Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")
private val dealPricePattern = Regex("(\\d+(?:\\.\\d{1,2})?)元，(到手价(?:，(?:同款低价|全网低价))?)")
private val discountPattern = Regex("满\\d+(?:\\.\\d{1,2})?已减\\d+(?:\\.\\d{1,2})?")

private fun ProbeNode.label(): String =
    (text ?: description).orEmpty().replace(formatCharacters, "").trim()

private fun ProbeSnapshot.visibleSubtree(root: ProbeNode): List<ProbeNode> {
    // 采样按深度优先遍历编号，父节点先于子节点。
    val subtree = mutableSetOf(root.index)
    return nodes.filter { node ->
        (node == root || node.parent in subtree && subtree.add(node.index)) && node.visible
    }
}

internal fun ProbeSnapshot.hasCollapsedMeituanOrder(): Boolean {
    val detail = nodes.singleOrNull { it.visible && it.resourceId == "$MEITUAN_PACKAGE:id/ifs" } ?: return false
    val rows = visibleSubtree(detail)
    val info = rows.singleOrNull { it.label() == "订单信息" } ?: return false
    val infoRows = visibleSubtree(nodes[info.parent])
    return infoRows.any { it.label() == "配送骑手/支付方式等" } && infoRows.none { it.label() == "订单号码" }
}

internal fun ProbeSnapshot.hasMeituanHome(): Boolean =
    nodes.any { it.visible && it.resourceId == "$MEITUAN_PACKAGE:id/fqa" && it.label() == "外卖" } &&
        nodes.any { it.visible && it.label() == "我的" } &&
        nodes.none { it.visible && it.resourceId == "$MEITUAN_PACKAGE:id/ifs" }

/** 订单须已展开，以便把状态与同一详情中的可见订单号码关联。 */
internal fun ProbeSnapshot.verifyMeituanOrder(expectedOrderNumber: String): MeituanOrderFields? {
    if (packageName != MEITUAN_PACKAGE || appVersion != MEITUAN_VERSION) return null
    val detail = nodes.singleOrNull { it.visible && it.resourceId == "$MEITUAN_PACKAGE:id/ifs" } ?: return null
    val rows = visibleSubtree(detail)
    val header = rows.singleOrNull { it.resourceId == "$MEITUAN_PACKAGE:id/r19" } ?: return null
    val status = visibleSubtree(header).singleOrNull { it.label() == "订单已完成" }?.label() ?: return null
    val info = rows.singleOrNull { it.label() == "订单信息" } ?: return null
    val infoRows = visibleSubtree(nodes[info.parent])
    val numberRow = infoRows.singleOrNull { it.label() == "订单号码" } ?: return null
    val number = visibleSubtree(nodes[numberRow.parent]).map { it.label().filterNot(Char::isWhitespace) }
        .singleOrNull(orderNumberPattern::matches)
    if (number != expectedOrderNumber) return null
    val timeRow = infoRows.singleOrNull { it.label() == "下单时间" }
    val orderTime = timeRow?.let { row ->
        visibleSubtree(nodes[row.parent]).map { it.label() }.singleOrNull(orderTimePattern::matches)
    }
    return MeituanOrderFields(status, orderTime, capturedAt)
}

/** 从同一商品详情容器读取展示字段，SKU 由调用方用独立凭据核验。 */
internal fun ProbeSnapshot.readJdProduct(expectedTitlePrefix: String, expectedVariant: String): JdProductFields? {
    if (packageName != JD_PACKAGE || appVersion != JD_VERSION) return null
    val detail = nodes.singleOrNull {
        it.visible && it.resourceId == "com.jd.lib.productdetail.feature:id/a6c"
    } ?: return null
    val fields = visibleSubtree(detail)
    val selected = fields.singleOrNull { it.className == "android.widget.Button" && it.label().startsWith("已选中款式 ") }
        ?.label()?.removePrefix("已选中款式 ") ?: return null
    if (selected != expectedVariant) return null
    val title = fields.singleOrNull {
        it.className == "android.widget.TextView" && it.label().startsWith(expectedTitlePrefix)
    }?.label() ?: return null
    val price = fields.singleOrNull {
        it.className == "android.view.ViewGroup" && dealPricePattern.matches(it.label())
    } ?: return null
    val (_, amount, priceLabel) = dealPricePattern.matchEntire(price.label())!!.groupValues
    val priceBlock = nodes[nodes[nodes[price.parent].parent].parent]
    val discount = visibleSubtree(priceBlock).map { it.label() }.singleOrNull(discountPattern::matches)
    return JdProductFields(title, selected, amount, "CNY", priceLabel, discount, capturedAt)
}
