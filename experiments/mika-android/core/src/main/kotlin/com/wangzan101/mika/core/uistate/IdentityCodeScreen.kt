package com.wangzan101.mika.core.uistate

/** 身份码任务只暴露导航标签。二维码像素、数字、订单内容和原始窗口树均不进入工具结果。 */
class IdentityCodeScreen(private val phone: ScreenPort, private val pickup: Boolean = false) : ScreenPort {
    private val titles = if (pickup) setOf("取包裹", "待取件", "待取包裹", "取件详情", "包裹详情", "取件码") else identityTitles
    private val entryLabels = if (pickup) titles + setOf("我的快递", "取包裹", "查快递", "我的包裹") else titles + setOf("出示身份码", "出示我的身份码")
    private var openedPackage: String? = null
    private var confirmedArrival: ScreenSnapshot? = null
    private var steadyDisplayHint = false
    private var visible: ScreenSnapshot? = null
    private var entry: Pair<String, UiElement>? = null
    private val blocked = ActionResult.Failed("target_unresolved")
    override val appAccess get() = phone.appAccess
    override suspend fun checkAppAccess() = phone.checkAppAccess()
    override suspend fun isProtected() = phone.isProtected()
    override suspend fun screenshotPng(): ByteArray? = null
    override suspend fun listApps() = phone.listApps()
    override suspend fun waitStable(timeoutMs: Long) = phone.waitStable(timeoutMs)
    override suspend fun awaitChange(timeoutMs: Long) = phone.awaitChange(timeoutMs)
    override val afterTask get() = phone.afterTask

    override suspend fun snapshot() = navigation(phone.snapshot()).also { visible = it }
    override suspend fun peek() = navigation(phone.peek())

    /** 末屏在切换时可短暂没有窗口或没有可用帧。等待当前显示稳定后仅重读一次。 */
    suspend fun readTerminal(): ScreenSnapshot {
        confirmedArrival = null
        steadyDisplayHint = false
        val page = peek()
        if (page.protected) return page
        // 刷新中的码值不作为页标。先后两读须来自同一应用，并且导航区域的位置与文字相同。
        fun anchors(s: ScreenSnapshot) = (headings(s) + s.elements.filter { it.name == "返回" || it.name == "暂无待取包裹" })
            .map { it.name to it.bounds }.distinct()
        fun confirm(first: ScreenSnapshot, second: ScreenSnapshot) {
            if (first.packageName == second.packageName && hasArrivalLandmarks(first) && hasArrivalLandmarks(second) && anchors(first) == anchors(second)) {
                confirmedArrival = second
                val hints = first.elements.map { it.name }.filter { it in displayHints }
                steadyDisplayHint = second.elements.any { it.name in hints }
            }
        }
        val first = hasArrivalLandmarks(page)
        if (!first && !page.changedDuringRead && page.hasWindow && page.packageName != null && page.captureError == null) return page
        waitStable(if (first) 300L else 1500L)
        val again = peek()
        if (first) {
            confirm(page, again)
        } else if (!first && hasArrivalLandmarks(again)) {
            waitStable(300L)
            val last = peek()
            confirm(again, last)
            return last
        }
        return again
    }

    /** 只依据同一稳定窗口中的标题和独立出示说明判定。无法确认时说明具体缺项。 */
    fun missingEvidence(page: ScreenSnapshot): String? {
        titleIssue(page)?.let { return it }
        return if (page.elements.any { it.name in displayHints }) null else "已读到身份码标题，缺少当前码的出示、扫码使用或动态刷新提示。"
    }

    /** 入口点击与终点都在这次运行内发生；返回、打开其他应用或另点一个控件后旧点击不再作证。 */
    fun arrived(page: ScreenSnapshot): Boolean {
        return (!page.changedDuringRead || confirmedArrival === page) && hasArrivalLandmarks(page)
    }

    fun confirmed(page: ScreenSnapshot) = confirmedArrival === page

    fun hasArrivalLandmarks(page: ScreenSnapshot): Boolean {
        if (!page.hasWindow || page.protected || page.captureError != null ||
            page.elements.any { it.name in conflicts }) return false
        // 官方首页本身可以直接显示个人待取列表的明确空态。仍须本轮打开该应用且连续两读一致。
        if (pickup && openedPackage != null && openedPackage == page.packageName &&
            page.elements.any { it.name == "暂无待取包裹" }) return true
        val (pkg, tapped) = entry ?: return false
        if (pkg != page.packageName) return false
        return headings(page).any { title -> (title.name != "包裹详情" || page.elements.any { it.name == "待取件" || it.name == "待取包裹" }) && besideBack(page, title) &&
            (tapped.clickable || tapped.role.equals("button", true) || title.bounds != tapped.bounds) }
    }

    private fun besideBack(page: ScreenSnapshot, title: UiElement) = page.elements.any { back ->
        back.name == "返回" && back.bounds.size == 4 && back.bounds[2] <= title.bounds[0] &&
            back.bounds[1] < title.bounds[3] && title.bounds[1] < back.bounds[3]
    }

    private fun titleIssue(page: ScreenSnapshot): String? {
        when {
            page.protected -> return "当前窗口受保护，未能核对身份码页。"
            !page.hasWindow -> return "当前任务屏没有可读取的应用窗口，未能核对身份码页。"
            page.packageName == null -> return "当前窗口未能识别所属应用，未能核对身份码页。"
            page.changedDuringRead && !(confirmedArrival === page && steadyDisplayHint) -> return "身份码页仍在变化，未能取得稳定读屏。"
            page.captureError != null -> return "当前任务屏画面不可用，未能核对身份码页。"
        }
        val labels = page.elements.map { it.name }
        if (labels.any { it in conflicts }) return "当前页含支付、登录或身份验证提示，未核实身份码展示页。"
        if ("码未展示" in labels) return "身份码尚未生成、加载失败或已经失效，未核实当前码。"
        if (headings(page).isEmpty()) return "未读到身份码展示页的标题。入口按钮不能证明已经打开。"
        return null
    }

    private fun headings(page: ScreenSnapshot): List<UiElement> {
        val buttons = page.elements.filter { it.name in titles && (it.clickable || it.role.equals("button", true)) }
        return page.elements.filter { e -> e.name in titles && !e.clickable && !e.editable && !e.role.equals("button", true) &&
            e.bounds.size == 4 && e.bounds[0] < 500 && e.bounds[3] <= 350 && buttons.none { b -> b.name == e.name && b.bounds.size == 4 &&
                e.bounds[0] >= b.bounds[0] && e.bounds[1] >= b.bounds[1] && e.bounds[2] <= b.bounds[2] && e.bounds[3] <= b.bounds[3] } }
    }

    private suspend fun navigation(page: ScreenSnapshot): ScreenSnapshot {
        if (page.packageName != null && page.packageName != entry?.first) entry = null
        // Android 的元素表合并了多个窗口，只用最上层当前应用窗口的引用，避免从下层借到标题或提示。
        val top = page.windows.maxByOrNull { it.layer }
        val refs = top?.let { window -> buildSet<String> {
            fun collect(node: UiTreeNode) {
                node.ref?.let { add(it) }
                node.children.forEach(::collect)
            }
            if (window.type == "application" && window.packageName == page.packageName) window.nodes.forEach(::collect)
        } }
        val nodes = page.elements.mapNotNull { e ->
            if (e.secret || refs != null && e.ref !in refs) return@mapNotNull null
            val text = e.name.trim()
            val name = when {
                e.editable && e.searchField -> "搜索"
                e.editable -> return@mapNotNull null
                pickup && (text == "等待取件" || text == "待领取" || text == "请及时取件") -> "待取件"
                pickup && listOf("暂无待取包裹", "暂无待取件", "没有待取包裹").any { it == text } -> "暂无待取包裹"
                text in navigationLabels || text in titles || text in entryLabels || text in conflicts -> text
                listOf("付款码", "付款二维码", "支付码", "支付密码", "向商家付款", "收付款", "身份验证", "扫码登录", "请先登录", "授权登录", "正在加载", "加载中", "网络异常").any { it in text } -> "待验证或支付"
                listOf("码已失效", "码已过期", "无法生成", "未生成", "未显示", "暂无二维码", "码不可用", "加载失败", "点击生成", "暂未开通").any { it in text } -> "码未展示"
                ("刷新" in text || "更新" in text) && listOf("身份码", "此码", "二维码", "条形码", "条码").any { it in text } -> "身份码刷新提示"
                listOf("出示此码", "出示该码", "出示本码", "出示您的身份码", "出示你的身份码", "请出示身份码", "扫码使用", "扫描此码", "扫描二维码验证", "凭此码取件").any { it in text } -> "身份码出示提示"
                else -> return@mapNotNull null
            }
            e.copy(name = name, resourceId = null, placeholder = null, keyShortcuts = emptyList())
        }
        // 自绘取件入口用本机 OCR 补齐，只向任务暴露公开导航文字和范围。
        val needed = if (pickup && page.hasWindow && !page.protected && page.captureError == null &&
            page.packageName != null && page.packageName == openedPackage && nodes.none { it.name in conflicts } &&
            (top == null || top.type == "application" && top.packageName == page.packageName) &&
            nodes.none { it.name in entryLabels || it.name == "暂无待取包裹" }) {
            phone.readVisibleText().orEmpty().filter { line ->
                line.text.trim() in entryLabels + "暂无待取包裹" && line.bounds.size == 4
            }.mapIndexed { index, line ->
                val name = line.text.trim()
                val box = line.bounds
                val x = (box[0]+box[2]) * (page.width-1) / 1998
                val y = (box[1]+box[3]) * (page.height-1) / 1998
                UiElement("@pickup$index", if (name == "暂无待取包裹") "text" else "button", name, x, y,
                    box, null, name != "暂无待取包裹", false, false)
            }
        } else emptyList()
        return page.copy(elements = nodes + needed, windows = emptyList(), web = null, opened = null)
    }

    /** 动作回执中的原控件名和提示也可能包含业务数据。 */
    private fun ActionResult.publicResult() = when (this) {
        is ActionResult.Ok -> copy(hint = null, relocatedTo = null, opened = null)
        is ActionResult.Failed -> copy(hint = null)
    }
    override suspend fun openApp(packageName: String, cold: Boolean): ActionResult {
        entry = null
        val opened = phone.openApp(packageName, cold).publicResult()
        openedPackage = packageName.takeIf { opened is ActionResult.Ok }
        return opened
    }
    override suspend fun back(): ActionResult {
        entry = null
        return phone.back().publicResult()
    }
    override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = phone.scroll(ref, snapshotId, down).publicResult()
    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = phone.swipe(x1, y1, x2, y2, durationMs).publicResult()
    override suspend fun tapRef(ref: String, snapshotId: String): ActionResult {
        val before = visible?.takeIf { it.snapshotId == snapshotId } ?: return blocked
        val known = before.elements.firstOrNull { it.ref == ref } ?: return blocked
        if (known.name in conflicts) return blocked
        val result = if (pickup && known.ref.startsWith("@pickup")) {
            val current = peek()
            if (current.packageName != before.packageName || current.elements.none {
                it.name == known.name && it.clickable && it.bounds == known.bounds
            }) return blocked
            phone.tapXY(known.centerX, known.centerY).publicResult()
        } else phone.tapRef(ref, snapshotId).publicResult()
        if (result is ActionResult.Ok) {
            entry = before.packageName?.takeIf { known.name in entryLabels && headings(before).none { besideBack(before, it) } &&
                before.hasWindow && !before.protected && !before.changedDuringRead && before.captureError == null &&
                result.appSwitched == null && before.elements.none { it.name in conflicts || it.name == "码未展示" } }?.let { it to known }
        }
        return result
    }
    override suspend fun tapXY(x: Int, y: Int) = blocked
    override suspend fun typeText(ref: String?, snapshotId: String?, text: String) =
        if (text in titles && visible?.elements?.any { it.editable && it.name == "搜索" && (ref == it.ref || ref == null && it.focused) } == true)
            phone.typeText(ref, snapshotId, text).publicResult() else blocked
    override suspend fun clearText(ref: String?, snapshotId: String?) = blocked
    override suspend fun pressKey(keyCode: Int) = blocked

    companion object {
        const val TERMINAL_EVENT = "identity_code_display"
        const val PICKUP_EVENT = "pickup_official_arrived"
        const val ARRIVAL_EVENT = "identity_code_page_arrived"
        const val ARRIVED_ANSWER = "已打开身份码页，码是否展示未核实。"
        private val displayHints = setOf("身份码出示提示", "身份码刷新提示")
        private val identityTitles = setOf("身份码", "我的身份码", "取件身份码", "驿站身份码", "菜鸟身份码")
        private val conflicts = setOf("付款码", "支付码", "收付款", "支付密码", "立即登录", "请先登录", "身份验证", "待验证或支付")
        private val navigationLabels = setOf("首页", "我的", "我的淘宝", "我的快递", "我的包裹", "查快递", "物流", "我的物流",
            "查看物流", "物流中心", "物流服务", "快递", "菜鸟", "菜鸟驿站", "菜鸟裹裹", "淘宝物流", "驿站", "取件", "取包裹",
            "待取件", "待取包裹", "出示身份码", "我的订单", "全部订单", "全部", "全部服务", "更多", "更多服务", "工具", "服务",
            "搜索", "搜索框", "搜索栏", "返回", "关闭", "取消", "淘宝", "手机淘宝",
            "个人主页", "个人中心", "个人资料", "个人信息", "账号", "账户与安全", "账号与安全", "设置", "会员", "会员中心",
            "我的二维码", "我的名片", "淘宝名片", "我的身份", "身份服务", "身份凭证", "用户中心", "出示我的身份码")
    }
}
