package com.wangzan101.mika.experiment

import android.app.ActivityManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

internal class AndroidRulePhone(
    private val context: Context,
    private val workspaceTask: ActivityManager.AppTask? = null,
) : RulePhone {
    private var nodes: List<AccessibilityNodeInfo> = emptyList()

    override fun installedVersion(packageName: String): String? = try {
        context.packageManager.getPackageInfo(packageName, 0).versionName
    } catch (e: PackageManager.NameNotFoundException) { null }

    override fun launch(target: RuleTarget, activity: String) {
        val service = ScreenAccessibility.instance.value ?: error("Mika Dev 无障碍服务未连接")
        service.startActivity(Intent(Intent.ACTION_MAIN).setClassName(target.packageName, activity)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun openMeituanOrder(order: MeituanOrderTarget) {
        val uri = Uri.Builder().scheme("imeituan").authority("www.meituan.com").path("/waimaiorder")
            .appendQueryParameter("oid", order.oid).build()
        // NEW_TASK 可能只把已有的美团首页带到前台，订单路由未必被分发。
        checkNotNull(workspaceTask) { "日常空间任务不可用" }
            .startActivity(context, Intent(Intent.ACTION_VIEW, uri).setPackage(MEITUAN_PACKAGE), null)
    }

    override suspend fun retryMeituanOrderFromHome(order: MeituanOrderTarget) {
        if (read(RuleTarget(MEITUAN_PACKAGE, MEITUAN_VERSION)).detail?.hasMeituanHome() != true) return
        workspaceTask?.moveToFront()
        val foreground = withTimeoutOrNull(1_500) {
            var name = read().packageName
            while (name == null || name == MEITUAN_PACKAGE) {
                delay(100)
                name = read().packageName
            }
            name
        }
        if (foreground == context.packageName) openMeituanOrder(order)
    }

    suspend fun captureJdAppPrice(product: JdProduct): Pair<JdProductFields, String>? {
        val target = RuleTarget(JD_PACKAGE, JD_VERSION)
        val task = workspaceTask ?: return null
        if (installedVersion(JD_PACKAGE) != JD_VERSION || read().packageName != context.packageName) return null
        suspend fun awaitPage(timeoutMs: Long = 6_000, allowSystemNavigation: Boolean = false,
                              matches: (RulePage) -> Boolean): RulePage? = withTimeoutOrNull(timeoutMs) {
            var page = read(target)
            while (!matches(page)) {
                val pkg = page.packageName
                val navigating = allowSystemNavigation && pkg in setOf("com.android.systemui", "com.bbk.launcher2")
                if (pkg != null && pkg != JD_PACKAGE && pkg != context.packageName && !navigating) {
                    return@withTimeoutOrNull null
                }
                delay(200)
                page = read(target)
            }
            page
        }
        fun RulePage.productFields() = detail?.readJdProduct(product.appTitlePrefix, product.variant)
        val first = awaitPage(timeoutMs = 30_000, allowSystemNavigation = true) {
            it.productFields() != null
        }?.productFields() ?: return null
        if (click(RuleSelector("分享", "com.jd.lib.productdetail.feature:id/anl")) != RuleClick.ACCEPTED) return null
        awaitPage { it.packageName == JD_PACKAGE && "复制链接" in it.labels } ?: return null
        val requestedAt = System.currentTimeMillis()
        if (click(RuleSelector("复制链接", "$JD_PACKAGE:id/dx")) != RuleClick.ACCEPTED) return null
        val last = awaitPage {
            val price = it.productFields()
            price != null && price.title == first.title && price.variant == first.variant
        }?.productFields() ?: return null
        if (read().packageName != JD_PACKAGE) return null
        task.moveToFront()
        awaitPage { it.packageName == context.packageName } ?: return null
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val clip = withTimeoutOrNull(2_000) {
            var description = clipboard.primaryClipDescription
            while (description == null) {
                if (read().packageName != context.packageName) return@withTimeoutOrNull null
                delay(100)
                description = clipboard.primaryClipDescription
            }
            if (description.timestamp < requestedAt || read().packageName != context.packageName) return@withTimeoutOrNull null
            clipboard.primaryClip
        } ?: return null
        val link = clip.getItemAt(0).text?.toString() ?: return null
        return last to link
    }

    override fun read(detailTarget: RuleTarget?): RulePage {
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
        val packageName = root?.packageName?.toString()
        // 选中的菜鸟首页标签有时只暴露图标。
        val cainiaoHomeSelected = packageName == CAINIAO_PACKAGE && visible.any { node ->
            node.viewIdResourceName == "$CAINIAO_PACKAGE:id/home_page_nav_nav_tab_icon_biz" &&
                generateSequence(node.parent) { it.parent }.any { it.isSelected }
        }
        return RulePage(packageName, visible.flatMap {
            listOfNotNull(it.text?.toString(), it.contentDescription?.toString())
        }.map { it.trim() }.filter { it.isNotEmpty() }.toSet(), visible.any { it.isPassword }, visible.size,
            if (root != null && detailTarget != null && packageName == detailTarget.packageName)
                root.probeSnapshot(detailTarget.packageName, detailTarget.testedAppVersion) else null, cainiaoHomeSelected)
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
