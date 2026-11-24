package com.life0423.carddock.platform

import com.life0423.carddock.core.model.PickupEntry
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityWindowInfo
import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 只在主动读取时观察当前窗口；事件回调不采集、不缓存页面。 */
class CardDockAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    // onInterrupt 仅表示反馈被打断，不代表系统解绑。
    override fun onInterrupt() = Unit
    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** 单次观察结束即释放所有节点，密码节点仅记录存在性，不读取文本。 */
    @Suppress("DEPRECATION")
    internal fun observe(displayId: Int? = null, pickup: Boolean = false): CainiaoObservation? {
        val root = (if (displayId == null) rootInActiveWindow else displayRoot(displayId, CAINIAO_PACKAGE)) ?: return null
        val packageName = root.packageName?.toString()
        if (packageName != CAINIAO_PACKAGE) {
            root.recycle()
            return CainiaoObservation(packageName, emptySet(), false)
        }
        val labels = mutableSetOf<String>()
        var hasPending = false
        var password = false
        fun visit(node: AccessibilityNodeInfo) {
            try {
                if (node.isVisibleToUser && node.packageName?.toString() == CAINIAO_PACKAGE) {
                    if (node.isPassword) password = true
                    else listOfNotNull(node.text, node.contentDescription).forEach { value ->
                        val text = value.toString().trim()
                        // 仅留下核验锚点；手机号、验证码等实际正文不进入观察结果。
                        if (pickup && listOf("申请授权","同意授权","身份码授权","已失效","已过期","加载失败","网络异常").any(text::contains))
                            labels.add("待处理")
                        val pending = !pickup && pickupCountText.matches(text)
                        when {
                            pickup && PickupEntry.entries.any { it.label == text } -> labels.add(text)
                            text == "我的" -> labels.add(text)
                            pending -> hasPending = true
                            pickup && text.startsWith("点击可") && text.endsWith("刷新") && text.length <= 12 -> labels.add("身份码刷新提示")
                            text in setOf("取包裹", "暂无待取包裹", "手机号登录", "登录/注册", "登录") -> labels.add(text)
                            text.contains("手机号") -> labels.add("手机号")
                            text.contains("验证码") -> labels.add("验证码")
                        }
                    }
                }
                if (node.isPassword) return
                for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
            } finally {
                node.recycle()
            }
        }
        visit(root)
        return CainiaoObservation(packageName, labels, password, hasPending)
    }

    /** 只读取请求的活动窗口。系统浮窗和其他应用不会提供详情证据。 */
    @Suppress("DEPRECATION")
    internal fun observeDetail(expected: String, displayId: Int? = null, geometry: Boolean = false): DetailWindow? {
        val root = (if (displayId == null) rootInActiveWindow else displayRoot(displayId, expected)) ?: return null
        return readDetail(root, expected, geometry)
    }

    @Suppress("DEPRECATION")
    private fun readDetail(root: AccessibilityNodeInfo, expected: String, geometry: Boolean = false): DetailWindow {
        val source = root.packageName?.toString()
        if (source != expected) {
            root.recycle()
            return DetailWindow(source, emptyList(), false)
        }
        val rows = mutableListOf<DetailNode>()
        var password = false
        fun visit(node: AccessibilityNodeInfo, parent: Int) {
            try {
                if (node.packageName?.toString() != expected) return
                if (node.isPassword) {
                    password = password || node.isVisibleToUser
                    return
                }
                val index = rows.size
                val visible = node.isVisibleToUser
                val label = if (visible) (node.text ?: node.contentDescription)?.toString().orEmpty()
                    .replace(formatCharacters, "").trim() else ""
                val bounds = if (geometry) android.graphics.Rect().also(node::getBoundsInScreen) else null
                rows += DetailNode(parent, node.viewIdResourceName, node.className?.toString(), label, visible, bounds, node.isScrollable, node.isSelected,
                    node.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD),
                    node.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD),
                    node.collectionInfo?.rowCount, node.collectionItemInfo?.rowIndex, node.isClickable, node.drawingOrder, node.isChecked, node.isEnabled)
                for (child in 0 until node.childCount) node.getChild(child)?.let { visit(it, index) }
            } finally {
                node.recycle()
            }
        }
        visit(root, -1)
        return DetailWindow(source, rows, password)
    }

    /** 在指定屏检查当前来源。交接后显式核对主屏 0，后台查询仍使用助手创建的屏号。 */
    @Suppress("DEPRECATION")
    private fun displayRoot(displayId: Int, expected: String): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT < 30 || displayId < 0) return null
        val displays = windowsOnAllDisplays
        val windows = displays[displayId].orEmpty()
        var result: AccessibilityNodeInfo? = null
        for (window in windows) {
            if (result == null && window.type == AccessibilityWindowInfo.TYPE_APPLICATION && window.isFocused) {
                val root = window.root
                if (root?.packageName?.toString() == expected) result = root else root?.recycle()
            }
        }
        for (index in 0 until displays.size()) displays.valueAt(index).forEach { it.recycle() }
        return result
    }

    /** 在指定查询屏定位可见节点，优先点击控件，必要时向该屏分派手势。 */
    internal fun tapReadTarget(displayId: Int, expected: String, target: DetailNode, actionBounds: android.graphics.Rect? = null,
        keepGestureOnLabel: Boolean = false, receiptTarget: Boolean = false): Boolean {
        if (displayId <= 0 || Build.VERSION.SDK_INT < 30) return false
        val box = target.bounds ?: return false
        val root = displayRoot(displayId, expected) ?: return false
        val matches = root.findAccessibilityNodeInfosByText(target.label)
        var point = box
        try {
            val hit = matches.singleOrNull { it.isVisibleToUser && android.graphics.Rect().also(it::getBoundsInScreen) == box }
            if (hit == null && !receiptTarget) return false
            var current: AccessibilityNodeInfo? = hit?.let { AccessibilityNodeInfo.obtain(it) }
            repeat(5) {
                val n = current ?: return@repeat
                val bounds = android.graphics.Rect().also(n::getBoundsInScreen)
                val allowed = actionBounds?.contains(bounds) ?: (bounds.width() <= box.width()*6 && bounds.height() <= box.height()*6)
                if (allowed && !target.label.startsWith("下单时间") && n.isClickable) {
                    val ok = n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    n.recycle(); current=null
                    if (ok) return true
                    if (!keepGestureOnLabel) point=bounds
                } else { current=n.parent; n.recycle() }
            }
            current?.recycle()
        } finally { matches.forEach { it.recycle() }; root.recycle() }
        // 补点账单日期或官方原单回链前，按当前页面重新核实标签归属与遮挡情况。
        if (receiptTarget && observeDetail(expected,displayId,geometry=true)?.exposesReceiptTarget(target) != true) return false
        val path = android.graphics.Path().apply { moveTo(point.exactCenterX(),point.exactCenterY()) }
        val gesture = android.accessibilityservice.GestureDescription.Builder().setDisplayId(displayId)
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 70)).build()
        return dispatchGesture(gesture, null, null)
    }

    /** 只在淘宝查询屏上向唯一的搜索框输入关键词，按钮也按官方控件 ID 定位。 */
    internal fun bookControl(screen: Int, id: String, value: String? = null): Boolean {
        val pkg = com.life0423.carddock.TAOBAO
        val root = displayRoot(screen, pkg) ?: return false
        val nodes = root.findAccessibilityNodeInfosByViewId("$pkg:id/$id")
        try {
            val node = nodes.singleOrNull { it.isVisibleToUser && it.isEnabled && it.packageName == pkg } ?: return false
            if (value == null) return node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (id != "searchEdit" || !node.isEditable || node.isPassword) return false
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            })
        } finally {
            nodes.forEach { it.recycle() }
            root.recycle()
        }
    }

    /** 在来源集合的可见边界内滚动半屏，保留重叠行供调用方逐单核验。 */
    internal fun advanceCollection(displayId: Int, source: String, box: android.graphics.Rect): Boolean {
        if (displayId <= 0 || android.os.Build.VERSION.SDK_INT < 30) return false
        val root = displayRoot(displayId, source) ?: return false
        root.recycle()
        if (box.isEmpty) return false
        val path = android.graphics.Path().apply {
            moveTo(box.exactCenterX(), box.top + box.height() * .72f)
            lineTo(box.exactCenterX(), box.top + box.height() * .32f)
        }
        return dispatchGesture(android.accessibilityservice.GestureDescription.Builder().setDisplayId(displayId)
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 480)).build(), null, null)
    }

    /** 仅点击同一后台窗口里唯一且可见的白名单入口。 */
    @Suppress("DEPRECATION")
    internal fun clickBackgroundLabel(displayId: Int, expected: String, label: String): Boolean {
        if (displayId <= 0) return false
        val root = displayRoot(displayId, expected) ?: return false
        return clickUniqueLabel(root, expected, label)
    }

    /** 检查主屏正在使用的来源，不读取其页面内容。 */
    @Suppress("DEPRECATION")
    internal fun primaryUses(source: String): Boolean {
        val window = displayRoot(0, source) ?: return false
        window.recycle()
        return true
    }

    /** 分类与搭配选项分别滚动官方页面的具名列表，不向购物车或主屏发送手势。 */
    @Suppress("DEPRECATION")
    internal fun scrollMealList(screen: Int?, detail: Boolean = false, backward: Boolean = false): Boolean {
        val pkg = com.life0423.carddock.ai.MCD_PACKAGE
        val root = (if (screen == null) rootInActiveWindow else displayRoot(screen, pkg)) ?: return false
        try {
            if (root.packageName != pkg) return false
            val lists = root.findAccessibilityNodeInfosByViewId(if (detail) "$pkg:id/sl_view" else "$pkg:id/rv_menu")
            try {
                return lists.singleOrNull { it.isVisibleToUser && it.isScrollable }
                    ?.performAction(if (backward) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
            } finally { lists.forEach { it.recycle() } }
        } finally { root.recycle() }
    }

    /** 购物袋图标支持查询屏预读和主屏回执；只在主屏对已确认的商品执行加购。 */
    internal fun toggleMealBag(screen: Int, store: String?) = clickMealControl(screen, "rl_shop_package") {
        it.mode == "到店取餐" && it.store == store && store != null
    }

    internal fun clickConfirmedMeal(target: com.life0423.carddock.core.rule.MealTarget, price: String) =
        clickMealControl(0, "rl_add_cart") { it.matchesCart(target, price) }

    @Suppress("DEPRECATION")
    private fun clickMealControl(screen: Int, id: String, matches: (MealPage) -> Boolean): Boolean {
        val pkg = com.life0423.carddock.ai.MCD_PACKAGE
        val root = displayRoot(screen, pkg) ?: return false
        try {
            if (!matches(MealPage(readDetail(AccessibilityNodeInfo.obtain(root), pkg)))) return false
            val controls = root.findAccessibilityNodeInfosByViewId("$pkg:id/$id")
            try {
                return controls.singleOrNull { it.isVisibleToUser && it.isClickable && it.isEnabled }
                    ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            } finally { controls.forEach { it.recycle() } }
        } finally { root.recycle() }
    }

    /** 仅点击官方已观测的商品行或分类行；加购控件是另一节点。 */
    @Suppress("DEPRECATION")
    internal fun clickMealMenuLabel(label: String, category: Boolean, screen: Int? = null): Boolean {
        val pkg = com.life0423.carddock.ai.MCD_PACKAGE
        val root = (if (screen == null) rootInActiveWindow else displayRoot(screen, pkg)) ?: return false
        try {
            if (root.packageName != pkg) return false
            val names = root.findAccessibilityNodeInfosByViewId("$pkg:id/tv_name")
            try {
                val node = names.singleOrNull { it.isVisibleToUser && it.text?.toString() == label } ?: return false
                val row = node.parent ?: return false
                try {
                    if (!category) return row.viewIdResourceName == "$pkg:id/rl_item" && row.isClickable &&
                        row.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (row.viewIdResourceName != "$pkg:id/rl_menu_item") return false
                    val owner = row.parent ?: return false
                    try { return owner.isClickable && owner.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                    finally { owner.recycle() }
                } finally { row.recycle() }
            } finally { names.forEach { it.recycle() } }
        } finally { root.recycle() }
    }

    /** 前台仅接受已核验的取件入口，按钮点击走官方控件；来源切换后立即停止。 */
    internal fun clickPickupEntry(entry: PickupEntry): Boolean {
        val root = rootInActiveWindow ?: return false
        return clickUniqueLabel(root, CAINIAO_PACKAGE, entry.label)
    }

    @Suppress("DEPRECATION")
    private fun clickUniqueLabel(root: AccessibilityNodeInfo, expected: String, label: String): Boolean {
        if (root.packageName?.toString() != expected) { root.recycle(); return false }
        // 美团的点击范围限定为当前详情树中唯一「订单信息」分组。
        val area = if (expected == MEITUAN_PACKAGE) {
            val snapshot = readDetail(AccessibilityNodeInfo.obtain(root), expected)
            if (!snapshot.hasCollapsedMeituanOrder()) {
                root.recycle()
                return false
            }
            val headings = root.findAccessibilityNodeInfosByText("订单信息")
            val parent = headings.singleOrNull { it.isVisibleToUser && it.text?.toString() == "订单信息" }?.parent
            headings.forEach { it.recycle() }
            if (parent == null) {
                root.recycle()
                return false
            }
            parent
        } else root
        val matches = area.findAccessibilityNodeInfosByText(label)
        val targets = mutableSetOf<AccessibilityNodeInfo>()
        try {
            for (node in matches) {
                if (!node.isVisibleToUser || node.packageName?.toString() != expected ||
                    node.text?.toString() != label && node.contentDescription?.toString() != label) continue
                var target: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
                while (target != null && !target.isClickable) {
                    val parent = if (target == area) null else target.parent
                    target.recycle()
                    target = parent
                }
                if (target != null && (!target.isVisibleToUser ||
                        target.packageName?.toString() != expected || !targets.add(target))) target.recycle()
            }
            return targets.singleOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        } finally {
            targets.forEach { it.recycle() }
            matches.forEach { it.recycle() }
            if (area !== root) area.recycle()
            root.recycle()
        }
    }

    companion object {
        private val mutableConnected = MutableStateFlow(false)
        internal val connected = mutableConnected.asStateFlow()
        @Volatile
        internal var instance: CardDockAccessibilityService? = null
            private set(value) {
                field = value
                mutableConnected.value = value != null
            }
    }
}
