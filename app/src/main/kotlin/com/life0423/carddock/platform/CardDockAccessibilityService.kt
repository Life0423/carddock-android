package com.life0423.carddock.platform

import android.content.Intent
import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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
    internal fun observe(): CainiaoObservation? {
        val root = rootInActiveWindow ?: return null
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
    internal fun observeDetail(expected: String): DetailWindow? {
        val root = rootInActiveWindow ?: return null
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
                rows += DetailNode(parent, node.viewIdResourceName, node.className?.toString(), label, visible)
                for (child in 0 until node.childCount) node.getChild(child)?.let { visit(it, index) }
            } finally {
                node.recycle()
            }
        }
        visit(root, -1)
        return DetailWindow(source, rows, password)
    }

    companion object {
        internal var instance: CardDockAccessibilityService? = null
            private set
    }
}
