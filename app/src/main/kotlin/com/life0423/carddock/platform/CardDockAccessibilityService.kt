package com.life0423.carddock.platform

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
    internal fun observe(displayId: Int? = null): CainiaoObservation? {
        val root = (if (displayId == null) rootInActiveWindow else backgroundRoot(displayId, CAINIAO_PACKAGE)) ?: return null
        val packageName = root.packageName?.toString()
        if (packageName != CAINIAO_PACKAGE) {
            root.recycle()
            return CainiaoObservation(packageName, emptySet(), false)
        }
        val labels = mutableSetOf<String>()
        var password = false
        fun visit(node: AccessibilityNodeInfo) {
            try {
                if (node.isVisibleToUser && node.packageName?.toString() == CAINIAO_PACKAGE) {
                    if (node.isPassword) password = true
                    else listOfNotNull(node.text, node.contentDescription).forEach { value ->
                        val text = value.toString().trim()
                        // 仅留下核验锚点；手机号、验证码等实际正文不进入观察结果。
                        when {
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
        return CainiaoObservation(packageName, labels, password)
    }

    /** 只读取请求的活动窗口。系统浮窗和其他应用不会提供详情证据。 */
    @Suppress("DEPRECATION")
    internal fun observeDetail(expected: String, displayId: Int? = null, geometry: Boolean = false): DetailWindow? {
        val root = (if (displayId == null) rootInActiveWindow else backgroundRoot(displayId, expected)) ?: return null
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
                rows += DetailNode(parent, node.viewIdResourceName, node.className?.toString(), label, visible, bounds, node.isScrollable, node.isSelected)
                for (child in 0 until node.childCount) node.getChild(child)?.let { visit(it, index) }
            } finally {
                node.recycle()
            }
        }
        visit(root, -1)
        return DetailWindow(source, rows, password)
    }

    /** 只访问目标虚拟屏上的指定应用窗口，零号屏不参与后台查询。 */
    @Suppress("DEPRECATION")
    private fun backgroundRoot(displayId: Int, expected: String): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT < 30 || displayId <= 0) return null
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
    internal fun tapReadTarget(displayId: Int, expected: String, target: DetailNode, actionBounds: android.graphics.Rect? = null): Boolean {
        if (displayId <= 0 || Build.VERSION.SDK_INT < 30) return false
        val box = target.bounds ?: return false
        val root = backgroundRoot(displayId, expected) ?: return false
        val matches = root.findAccessibilityNodeInfosByText(target.label)
        var point = box
        try {
            val hit = matches.singleOrNull { it.isVisibleToUser && android.graphics.Rect().also(it::getBoundsInScreen) == box } ?: return false
            var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(hit)
            repeat(5) {
                val n = current ?: return@repeat
                val bounds = android.graphics.Rect().also(n::getBoundsInScreen)
                val allowed = actionBounds?.contains(bounds) ?: (bounds.width() <= box.width()*6 && bounds.height() <= box.height()*6)
                    if (allowed && !target.label.startsWith("下单时间") && n.isClickable) {
                    val ok = n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    n.recycle(); current=null
                    if (ok) return true
                    point=bounds
                } else { current=n.parent; n.recycle() }
            }
            current?.recycle()
        } finally { matches.forEach { it.recycle() }; root.recycle() }
        val path = android.graphics.Path().apply { moveTo(point.exactCenterX(),point.exactCenterY()) }
        val gesture = android.accessibilityservice.GestureDescription.Builder().setDisplayId(displayId)
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 70)).build()
        return dispatchGesture(gesture, null, null)
    }

    /** 仅点击同一后台窗口里唯一且可见的白名单入口。 */
    @Suppress("DEPRECATION")
    internal fun clickBackgroundLabel(displayId: Int, expected: String, label: String): Boolean {
        val root = backgroundRoot(displayId, expected) ?: return false
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
