package com.wangzan101.mika.experiment

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility

internal class AndroidRulePhone(private val context: Context) : RulePhone {
    private var nodes: List<AccessibilityNodeInfo> = emptyList()

    override fun installedVersion(packageName: String): String? = try {
        context.packageManager.getPackageInfo(packageName, 0).versionName
    } catch (e: PackageManager.NameNotFoundException) { null }

    override fun launch(target: RuleTarget, activity: String) {
        val service = ScreenAccessibility.instance.value ?: error("Mika Dev 无障碍服务未连接")
        service.startActivity(Intent(Intent.ACTION_MAIN).setClassName(target.packageName, activity)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun read(): RulePage {
        val service = ScreenAccessibility.instance.value ?: error("执行期间无障碍服务断开")
        val root = service.windowsOn(Display.DEFAULT_DISPLAY)
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.layer }.firstNotNullOfOrNull { it.root }
        val visible = mutableListOf<AccessibilityNodeInfo>()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        root?.let(pending::add)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (node.isVisibleToUser) visible += node
            for (i in 0 until node.childCount) node.getChild(i)?.let(pending::add)
        }
        nodes = visible
        return RulePage(root?.packageName?.toString(), visible.flatMap {
            listOfNotNull(it.text?.toString(), it.contentDescription?.toString())
        }.map { it.trim() }.filter { it.isNotEmpty() }.toSet(), visible.any { it.isPassword }, visible.size)
    }

    override fun click(selector: RuleSelector): RuleClick {
        val candidates = nodes.filter {
            (it.text?.toString()?.trim() == selector.text || it.contentDescription?.toString()?.trim() == selector.text) &&
                (selector.resourceId == null || it.viewIdResourceName == selector.resourceId)
        }.mapNotNull { node ->
            var clickable: AccessibilityNodeInfo? = node
            while (clickable != null && !clickable.isClickable) clickable = clickable.parent
            clickable?.takeIf { it.isEnabled && it.isVisibleToUser }
        }.distinctBy { Rect().also(it::getBoundsInScreen).flattenToString() }
        return when (candidates.size) {
            0 -> RuleClick.NOT_FOUND
            1 -> if (candidates.single().performAction(AccessibilityNodeInfo.ACTION_CLICK)) RuleClick.ACCEPTED else RuleClick.REJECTED
            else -> RuleClick.AMBIGUOUS
        }
    }
}
