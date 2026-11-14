package com.life0423.carddock.platform

import com.life0423.carddock.core.model.AppPrice

internal const val MEITUAN_PACKAGE = "com.sankuai.meituan"
internal const val MEITUAN_TARGET = "meituan.completed-order"
internal const val JD_PACKAGE = "com.jingdong.app.mall"

private val orderNumber = Regex("[0-9]{15,30}")
private val orderTime = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}")
private val dealPrice = Regex("([0-9]+(?:\\.[0-9]{1,2})?)元，(到手价(?:，(?:同款低价|全网低价))?|同款低价)")
private val discount = Regex("满[0-9]+(?:\\.[0-9]{1,2})?已减[0-9]+(?:\\.[0-9]{1,2})?|已直降[0-9]+(?:\\.[0-9]{1,2})?元")
internal val formatCharacters = Regex("\\p{Cf}")

/** 当前活动窗的可见详情证据。节点只在一次主动读取中存在。 */
internal data class DetailNode(
    val parent: Int,
    val id: String?,
    val role: String?,
    val label: String,
    val visible: Boolean,
)
internal data class DetailWindow(val source: String?, val nodes: List<DetailNode>, val password: Boolean)
internal data class OrderEvidence(val number: String, val status: String, val time: String)
internal data class JdDetail(val title: String, val variant: String, val price: AppPrice?)

/** 采样采用深度优先编号，父节点在子节点之前。 */
private fun List<DetailNode>.visibleSubtree(root: Int, includeContainers: Boolean = false): List<IndexedValue<DetailNode>> {
    val indices = mutableSetOf(root)
    return withIndex().filter { (index, node) ->
        (index == root || node.parent in indices && indices.add(index)) && (node.visible || includeContainers)
    }
}

internal fun DetailWindow.isLogin(): Boolean {
    val labels = nodes.filter { it.visible }.map { it.label }
    return password || labels.any { it in setOf("手机号登录", "登录/注册", "登录美团", "登录京东") } ||
        ("登录" in labels && labels.any { "手机号" in it || "验证码" in it })
}

/** 完成状态、订单号和时间必须来自同一个订单详情容器。 */
internal fun DetailWindow.readOrder(): OrderEvidence? {
    val root = nodes.indices.singleOrNull {
        nodes[it].id == "$MEITUAN_PACKAGE:id/ii7" || nodes[it].visible && nodes[it].id == "$MEITUAN_PACKAGE:id/ifs"
    } ?: return null
    // 美团实测有两套详情结构。新布局的订单信息位于详情容器内独立的列表。
    val currentLayout = nodes[root].id == "$MEITUAN_PACKAGE:id/ii7"
    // vivo 会把结构容器标成不可见，核验字段仍须来自可见节点。
    val rows = nodes.visibleSubtree(root, includeContainers = currentLayout)
    val headerId = if (currentLayout) "sur" else "r19"
    val header = rows.singleOrNull { it.value.id == "$MEITUAN_PACKAGE:id/$headerId" } ?: return null
    val status = nodes.visibleSubtree(header.index).singleOrNull { it.value.label == "订单已完成" }?.value?.label ?: return null
    val infoContainer = if (currentLayout) {
        val list = rows.singleOrNull { it.value.id == "$MEITUAN_PACKAGE:id/kr1" } ?: return null
        nodes.visibleSubtree(list.index)
    } else rows
    val info = infoContainer.singleOrNull { it.value.visible && it.value.label == "订单信息" } ?: return null
    val infoRows = nodes.visibleSubtree(info.value.parent)
    val numberRow = infoRows.singleOrNull { it.value.label == "订单号码" } ?: return null
    val number = nodes.visibleSubtree(numberRow.value.parent).map { it.value.label.filterNot(Char::isWhitespace) }
        .singleOrNull(orderNumber::matches) ?: return null
    val timeRow = infoRows.singleOrNull { it.value.label == "下单时间" } ?: return null
    val time = nodes.visibleSubtree(timeRow.value.parent).map { it.value.label }.singleOrNull(orderTime::matches) ?: return null
    return OrderEvidence(number, status, time)
}

/** 商品身份标签用于监测切换；价格只读同一个详情容器和同一个价格区。 */
internal fun DetailWindow.readJdDetail(now: Long): JdDetail? {
    val root = nodes.indices.singleOrNull { nodes[it].visible && nodes[it].id == "com.jd.lib.productdetail.feature:id/a6c" }
        ?: return null
    val rows = nodes.visibleSubtree(root)
    val variant = rows.singleOrNull { it.value.role == "android.widget.Button" && it.value.label.startsWith("已选中款式 ") }
        ?.value?.label?.removePrefix("已选中款式 ") ?: return null
    val title = rows.singleOrNull { (_, node) ->
        node.role == "android.widget.TextView" && JdProduct.entries.any { node.label.startsWith(it.titlePrefix) }
    }?.value?.label ?: return null
    val priceRow = rows.singleOrNull { it.value.role == "android.view.ViewGroup" && dealPrice.matches(it.value.label) }
    val price = priceRow?.let { row ->
        val (amount, label) = checkNotNull(dealPrice.matchEntire(row.value.label)).destructured
        val block = nodes.getOrNull(row.value.parent)?.let { nodes.getOrNull(it.parent) }?.parent
        val condition = block?.let { nodes.visibleSubtree(it).map { entry -> entry.value.label }.singleOrNull(discount::matches) }
        AppPrice(amount, "CNY", variant, label, condition, now)
    }
    return JdDetail(title, variant, price)
}
