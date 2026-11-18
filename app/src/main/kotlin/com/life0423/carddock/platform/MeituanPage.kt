package com.life0423.carddock.platform

import android.view.accessibility.AccessibilityNodeInfo

internal enum class MeituanPage { WAITING, HOME, NEEDS_LOGIN, NEEDS_JUMP_APPROVAL, WRONG_ORDER, EXPANDED, CONFIRMED }

private const val DETAIL_ID = "$MEITUAN_PACKAGE:id/ifs"
private const val HEADER_ID = "$MEITUAN_PACKAGE:id/r19"
private const val HOME_TAB_ID = "$MEITUAN_PACKAGE:id/fqa"
private val formatCharacters = Regex("\\p{Cf}")
private val loginLabels = setOf("手机号登录", "验证码登录")

/** 在同一订单详情内查找完成状态和订单号码。采样节点不传回会话层。 */
@Suppress("DEPRECATION")
internal fun CardDockAccessibilityService.inspectMeituanPage(orderNumber: String, allowExpansion: Boolean): MeituanPage {
    val root = rootInActiveWindow ?: return MeituanPage.WAITING
    val nodes = mutableListOf<OrderNode>()
    try {
        when (root.packageName?.toString()) {
            "com.vivo.appfilter" -> return MeituanPage.NEEDS_JUMP_APPROVAL
            MEITUAN_PACKAGE -> Unit
            else -> return MeituanPage.WAITING
        }
        fun visit(info: AccessibilityNodeInfo, parent: OrderNode?) {
            if (info.packageName?.toString() != MEITUAN_PACKAGE) {
                info.recycle()
                return
            }
            val node = OrderNode(info, parent)
            nodes += node
            parent?.children?.add(node)
            if (!info.isPassword) for (index in 0 until info.childCount) info.getChild(index)?.let { visit(it, node) }
        }
        visit(root, null)
        val visible = nodes.filter { it.info.isVisibleToUser }
        if (visible.any { it.info.isPassword || it.label in loginLabels }) return MeituanPage.NEEDS_LOGIN
        val details = visible.filter { it.info.viewIdResourceName == DETAIL_ID }
        if (details.isEmpty()) {
            return if (visible.any { it.info.viewIdResourceName == HOME_TAB_ID && it.label == "外卖" } && visible.any { it.label == "我的" }
            ) MeituanPage.HOME else MeituanPage.WAITING
        }
        val detail = details.singleOrNull() ?: return MeituanPage.WAITING
        val rows = detail.visibleSubtree()
        val header = rows.singleOrNull { it.info.viewIdResourceName == HEADER_ID } ?: return MeituanPage.WAITING
        if (header.visibleSubtree().count { it.label == "订单已完成" } != 1) return MeituanPage.WAITING
        val orderInfo = rows.singleOrNull { it.label == "订单信息" }?.parent ?: return MeituanPage.WAITING
        val infoRows = orderInfo.visibleSubtree().toList()
        val numberLabels = infoRows.filter { it.label == "订单号码" }
        if (numberLabels.isEmpty() && allowExpansion) {
            val control = infoRows.singleOrNull { it.label == "配送骑手/支付方式等" } ?: return MeituanPage.WAITING
            // 仅允许点击同一个「订单信息」块里的明确展开控件。
            val button = generateSequence(control) { if (it === orderInfo) null else it.parent }
                .firstOrNull { it.info.isClickable && it.info.isVisibleToUser && it.info.isEnabled }
            if (button?.info?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return MeituanPage.EXPANDED
        }
        val numberRow = numberLabels.singleOrNull()?.parent ?: return MeituanPage.WAITING
        val observedNumber = numberRow.visibleSubtree().map { it.label.filterNot(Char::isWhitespace) }
            .singleOrNull(meituanOrderNumberPattern::matches) ?: return MeituanPage.WAITING
        return if (observedNumber == orderNumber) MeituanPage.CONFIRMED else MeituanPage.WRONG_ORDER
    } finally {
        if (nodes.isEmpty()) root.recycle() else nodes.forEach { it.info.recycle() }
    }
}

private class OrderNode(val info: AccessibilityNodeInfo, val parent: OrderNode?) {
    val children = mutableListOf<OrderNode>()
    val label: String get() = if (info.isPassword) "" else (info.text ?: info.contentDescription)
        ?.toString().orEmpty().replace(formatCharacters, "").trim()

    fun visibleSubtree(): Sequence<OrderNode> = sequence {
        if (info.isVisibleToUser) yield(this@OrderNode)
        for (child in children) yieldAll(child.visibleSubtree())
    }
}
