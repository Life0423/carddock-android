package com.wangzan101.mika.experiment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

internal val probePackages = setOf(CAINIAO_PACKAGE, "com.sankuai.meituan", "com.jingdong.app.mall", "com.xunmeng.pinduoduo")

@Serializable
internal data class ProbeNode(
    val index: Int, val parent: Int, val text: String?, val description: String?, val resourceId: String?,
    val className: String?, val bounds: String, val visible: Boolean, val clickable: Boolean,
    val scrollable: Boolean, val editable: Boolean, val password: Boolean,
)

/** 在调试版中读取指定应用的当前前台窗口，保留节点层级供本机分析。 */
class PageProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val expected = intent.getStringExtra("expectedPackage") ?: return
        val sample = intent.getStringExtra("sampleId") ?: return
        if (expected !in probePackages || !sample.matches(Regex("[A-Za-z0-9_-]{1,64}"))) return
        val service = ScreenAccessibility.instance.value
        val root = service?.windowsOn(Display.DEFAULT_DISPLAY)
            ?.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            ?.sortedByDescending { it.layer }?.firstNotNullOfOrNull { it.root }
        val actual = root?.packageName?.toString()
        val dir = File(context.getExternalFilesDir(null), "page-probe").apply { mkdirs() }
        if (actual != expected) {
            File(dir, "status.json").writeText("""{"outcome":"REFUSED","expectedPackage":"$expected","actualPackage":${actual?.let { ruleJson.encodeToString(it) } ?: "null"}}""")
            return
        }
        val nodes = mutableListOf<ProbeNode>()
        fun visit(node: AccessibilityNodeInfo, parent: Int) {
            val index = nodes.size
            nodes += ProbeNode(index, parent, node.text?.toString()?.takeUnless { node.isPassword },
                node.contentDescription?.toString()?.takeUnless { node.isPassword }, node.viewIdResourceName,
                node.className?.toString(), Rect().also(node::getBoundsInScreen).flattenToString(),
                node.isVisibleToUser, node.isClickable, node.isScrollable, node.isEditable, node.isPassword)
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, index) }
        }
        visit(root!!, -1)
        val version = context.packageManager.getPackageInfo(expected, 0).versionName.orEmpty()
        val result = ProbeSnapshot(System.currentTimeMillis(), expected, version, nodes)
        File(dir, "$sample.json").writeText(ruleJson.encodeToString(result))
        File(dir, "status.json").writeText("""{"outcome":"CAPTURED","sampleId":"$sample","packageName":"$expected","nodeCount":${nodes.size}}""")
    }
}

@Serializable
internal data class ProbeSnapshot(val capturedAt: Long, val packageName: String, val appVersion: String, val nodes: List<ProbeNode>)
