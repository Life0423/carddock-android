package com.life0423.carddock

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.task.DockRoutes
import org.junit.Test

/** 按需读取真机原记录。仅输出状态、导航证据和本地编号，避免将包裹信息写入验收日志。 */
class Route32ReceiptTest {
    /** 同一助手连接内运行两次正式卡；只有真实旧页、两次返回和重走三步才能证明恢复。 */
    @Test fun replayKeptTaobaoDetail() {
        val card = InstrumentationRegistry.getArguments().getString("keptTaobaoCard") ?: return
        val ins = InstrumentationRegistry.getInstrumentation()
        val app = ins.targetContext.applicationContext as MikaApp
        val rows = DockRoutes(app)
        val pkg = "com.taobao.taobao"
        val saved = requireNotNull(rows.get(card))
        check(saved.isCard && !saved.busy && saved.route?.packages == listOf(pkg) &&
            saved.route?.steps?.map { it.target?.name } == listOf(null, "菜鸟驿站", "身份码"))
        val records = RunStore(app)
        val shared = com.wangzan101.mika.cap.phone.PhoneServices.of(app).sharedShell
        shared.acquire()
        var recovered = false
        try {
            repeat(2) { round ->
                rows.execute(card)
                val taskId = requireNotNull(rows.get(card)).taskId
                val end = (1..240).firstNotNullOfOrNull {
                    val task = app.remoteTasks.localTasks.get(taskId)
                    if (task?.runId != null && rows.get(card)?.busy == false) task else { Thread.sleep(1000); null }
                } ?: error("第 ${round + 1} 轮未收到正式卡终态")
                val events = (1..20).firstNotNullOfOrNull {
                    val log = records.events(requireNotNull(end.runId))
                    if (log.any { it is RunEvent.State && it.name == "apps_after_task" }) log else { Thread.sleep(250); null }
                } ?: error("第 ${round + 1} 轮没有完成虚拟屏收尾回执")
                val states = events.filterIsInstance<RunEvent.State>()
                val settled = states.lastOrNull { it.name == "apps_after_task" }?.detail.orEmpty()
                val kept = pkg in settled.substringBefore(';').removePrefix("kept ").split(',')
                val arrived = states.any { it.name == "identity_code_page_arrived" && it.detail == pkg }
                val recovery = states.filter { it.name == "entry_recovery" }
                val replay = events.filterIsInstance<RunEvent.Replay>().filter { it.matched && !it.skipped }.map { it.step }
                val actions = events.filterIsInstance<RunEvent.ActionTarget>().filter { it.source == "replay" && it.effective }.map { it.tool }
                val launch = events.filterIsInstance<RunEvent.ToolResult>().firstOrNull { it.tool == "phone_launch_app" }
                val result = launch?.let { org.json.JSONObject(it.resultJson) }
                val screen = result?.optJSONObject("screen") ?: result
                val nodes = screen?.optJSONArray("elements")
                val hasDetail = (0 until (nodes?.length() ?: 0)).mapNotNull { nodes?.optJSONObject(it) }
                    .filter { it.optString("name") == "身份码" }.map { it.optString("role") }.toSet().containsAll(setOf("TextView", "WebView"))
                ins.sendStatus(0, Bundle().apply { putString("receipt", "round=${round + 1} task=$taskId run=${end.runId} " +
                    "kept=$kept arrived=$arrived launchDetail=$hasDetail backs=${recovery.size} steps=$replay") })
                check(end.state is TaskState.Succeeded && arrived && replay == listOf(1, 2, 3))
                if (round == 0) check(kept) { "正式任务未保留淘宝虚拟屏任务" }
                if (hasDetail) {
                    check(recovery.size == 2 && recovery.first().detail.startsWith("原路线终点页") &&
                        actions == listOf("phone_launch_app", "phone_back", "phone_back", "phone_tap", "phone_tap")) {
                        "启动于身份码页后缺少两次返回或完整路线"
                    }
                    recovered = true
                }
            }
            org.junit.Assume.assumeTrue("两次启动均未恢复到身份码页，未覆盖旧终点分支", recovered)
        } finally { shared.release() }
    }

    /** 只创建验收自己的临时卡。真实取消回执抵达后才允许删除这张卡。 */
    @Test fun stopNewInstance() {
        val arg = InstrumentationRegistry.getArguments()
        val template = arg.getString("stopTestTemplate") ?: return
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MikaApp
        val rows = DockRoutes(app)
        val source = requireNotNull(rows.get(template))
        val siblings = rows.all().filter { it.isCard && it.templateId == template }.map { it.id }.toSet()
        val card = rows.createAndRun(template, source.slots)
        val taskId = requireNotNull(rows.get(card)).taskId
        // 等到服务真正运行，区分收到取消请求和最终停止。
        for (poll in 0 until 80) {
            if (app.remoteTasks.localTasks.get(taskId)?.state is TaskState.Running) break
            Thread.sleep(100)
        }
        check(rows.get(card)?.busy == true)
        rows.stop(card, delete = true)
        for (poll in 0 until 80) {
            if (rows.get(card) == null) break
            rows.reconcile(taskId)
            Thread.sleep(150)
        }
        check(rows.get(card) == null)
        check(app.remoteTasks.localTasks.get(taskId)?.state == TaskState.Cancelled)
        check(rows.get(template)?.isTemplate == true && siblings.all { rows.get(it)?.isCard == true })
    }

    /** 在正式卡运行时只截取任务虚拟屏上的京东筛选按钮；面板打开成功后才导出。 */
    @Test fun exportVerifiedFilterControl() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val card = InstrumentationRegistry.getArguments().getString("filterCard") ?: return
        val app = ins.targetContext.applicationContext as MikaApp
        val rows = DockRoutes(app)
        val pkg = "com.jingdong.app.mall"
        check(rows.get(card)?.route?.packages == listOf(pkg))
        rows.execute(card)
        val task = requireNotNull(rows.get(card)).taskId
        var crop: android.graphics.Bitmap? = null
        val displays = app.getSystemService(android.hardware.display.DisplayManager::class.java)
        for (poll in 0 until 450) {
            if (rows.get(card)?.busy == false) break
            Thread.sleep(400)
            val run = app.taskState.runs.value.firstOrNull { it.mcpExternalId == task } ?: continue
            val id = run.displayId.value
            if (id <= 0) continue
            val a11y = com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility.instance.value ?: continue
            val root = a11y.windowsOn(id).maxByOrNull { it.layer }?.root ?: continue
            if (root.packageName?.toString() != pkg || root.findAccessibilityNodeInfosByText("销量").isEmpty()) continue
            val buttons = root.findAccessibilityNodeInfosByText("筛选").filter {
                it.isVisibleToUser && it.className?.toString()?.endsWith("Button") == true &&
                    (it.text?.toString() == "筛选" || it.contentDescription?.toString() == "筛选")
            }
            val btn = buttons.singleOrNull() ?: continue
            val rect = android.graphics.Rect().also(btn::getBoundsInScreen)
            val frame = run.frames.value ?: continue
            val size = android.graphics.Point().also { displays.getDisplay(id)?.getRealSize(it) }
            if (size.x <= 0 || size.y <= 0 || rect.isEmpty || rect.left < 0 || rect.top < 0 ||
                rect.right > size.x || rect.bottom > size.y || rect.width() > size.x / 3 || rect.height() > size.y / 8) continue
            crop?.recycle()
            crop = android.graphics.Bitmap.createBitmap(frame, rect.left * frame.width / size.x,
                rect.top * frame.height / size.y, rect.width() * frame.width / size.x, rect.height() * frame.height / size.y)
        }
        val result = requireNotNull(rows.get(card))
        val events = RunStore(app).events(requireNotNull(app.remoteTasks.localTasks.get(task)?.runId))
        check(result.phase == com.wangzan101.mika.task.DockRoute.Phase.Ready && events.filterIsInstance<RunEvent.Observed>()
            .any { (it.replayEntrySource != null || it.verifiedVisualAction) && "重置" in it.text && "确定" in it.text }) { "本轮未核实筛选面板" }
        val image = requireNotNull(crop) { "未取得本轮筛选入口的画面" }
        java.io.File(app.getExternalFilesDir(null), "filter-button.png").outputStream().use {
            check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        image.recycle()
        ins.sendStatus(0, Bundle().apply { putString("receipt", "filterTask=$task") })
    }

    /** 仅比较现有京东卡导航参数与实际公开按钮，不导出用户的查询文字。 */
    @Test fun inspectShoppingSelectors() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val id = InstrumentationRegistry.getArguments().getString("shoppingCard") ?: return
        val row = DockRoutes(ins.targetContext).get(id) ?: return
        val route = row.route?.takeIf { it.packages == listOf("com.jingdong.app.mall") } ?: return
        val labels = setOf("搜索", "搜索 按钮", "搜索栏", "筛选", "问东东", "东东", "搜索京东商品")
        for ((i, step) in route.steps.withIndex()) {
            val sel = step.target ?: continue
            val slot = sel.name.removePrefix("{").removeSuffix("}").toIntOrNull()
            val name = slot?.let { row.slots.getOrNull(it - 1) } ?: sel.name
            ins.sendStatus(0, Bundle().apply { putString("receipt",
                "step=${i + 1} label=${name.takeIf { it in labels }} role=${sel.role} bounds=${sel.bounds} unique=${sel.uniqueAtRecord}") })
        }
    }

    /** 诊断历史控件名与现场的对应关系，只导出公开导航词和结构。 */
    @Test fun inspectNavigation() {
        val task = InstrumentationRegistry.getArguments().getString("navigationTask") ?: return
        val ins = InstrumentationRegistry.getInstrumentation()
        val app = ins.targetContext.applicationContext as MikaApp
        val row = app.remoteTasks.localTasks.get(task) ?: return
        val run = row.runId ?: return
        val allowed = setOf("身份码", "出示身份码", "取件", "取件页面", "暂无待取包裹", "取件码", "筛选", "搜索栏", "搜索", "搜索 按钮", "返回", "我的淘宝", "待收货",
            "我的快递", "菜鸟", "包裹", "登录", "取消", "同意", "首页", "东东", "问东东", "拍照购", "删除", "确定", "重置", "综合", "评价", "销量")
        fun emit(value: String) = ins.sendStatus(0, Bundle().apply { putString("receipt", value) })
        // 用本地序号关联同一查找和点击，不输出页面标识、搜索词或控件描述原文。
        val ids = mutableMapOf<String, Int>()
        fun id(value: String) = if (value.isBlank()) 0 else ids.getOrPut(value) { ids.size + 1 }
        fun shape(text: String) = listOf("漏斗", "图标", "筛选").filter { it in text }
        for (e in RunStore(app).events(run)) when (e) {
            is RunEvent.ModelReply -> if (e.tool == "phone_find_visually") {
                val args = org.json.JSONObject(e.argsJson ?: "{}")
                emit("find=${e.step} target=${shape(args.optString("target"))} label=${shape(args.optString("text"))}")
            }
            is RunEvent.Observed -> if (e.replayEntrySource != null || e.verifiedVisualAction)
                emit("entryReceipt=${e.id} source=${e.replayEntrySource ?: "vision"} terms=${shape(e.text)} panel=${listOf("重置", "确定").filter { it in e.text }}")
            is RunEvent.ActionTarget -> {
                val sel = e.target
                if (sel?.name == "筛选") {
                    val args = org.json.JSONObject(e.argsJson)
                    emit("filterTap=${e.step} ref=${id(args.optString("ref"))} snapshot=${id(args.optString("snapshotId"))} description=${shape(sel.visualDescription.orEmpty())}")
                }
                emit("action=${e.step} ${e.tool} target=${sel?.name?.takeIf { it in allowed }} role=${sel?.role} box=${sel?.bounds} before=${e.before.texts.filter { it in allowed }} after=${e.after.texts.filter { it in allowed }}")
            }
            is RunEvent.ToolResult -> {
                val data = runCatching { org.json.JSONObject(e.resultJson) }.getOrNull() ?: continue
                if (e.tool == "phone_find_visually" && data.optString("reason") == "name_mismatch") {
                    val message = data.optString("message")
                    val origin = when {
                        message.startsWith("The closest match reads") -> "visual_description"
                        message.startsWith("The spot that looked like it is labelled") -> "tree_occupant"
                        else -> "unspecified"
                    }
                    val seen = message.substringAfter('"', "").substringBefore('"').lowercase()
                    val words = listOf("漏斗", "筛选", "过滤", "图标", "funnel", "filter", "icon").filter { it in seen }
                    emit("nameMismatch=${e.step} origin=$origin publicTerms=$words")
                }
                if (e.tool == "phone_find_visually") emit("visual=${e.step} found=${data.optBoolean("found")} source=${data.optString("source")} reason=${data.optString("reason")} unverified=${data.optBoolean("unverified")} ref=${id(data.optString("ref"))} snapshot=${id(data.optString("snapshotId"))} box=${data.optJSONArray("box")} cropVerified=${"current control crop" in data.optString("note")} seen=${shape(data.optString("seen"))}")
                if (e.tool == "phone_screenshot") emit("screenshot=${e.step} ok=${data.optBoolean("ok")} frame=${data.optInt("screenshotId", -1)}")
                val page = data.optJSONObject("screen") ?: data
                val nodes = page.optJSONArray("elements") ?: continue
                for (i in 0 until nodes.length()) {
                    val node = nodes.optJSONObject(i) ?: continue
                    val name = node.optString("name").trim()
                    if (page.optString("currentApp") == "com.jingdong.app.mall" && node.optBoolean("editable"))
                        emit("searchField=${e.step} role=${node.optString("role")} hint=${node.optBoolean("hint")} blank=${name.isEmpty()}")
                    if (name in allowed) emit("node=${e.step} $name role=${node.optString("role")} box=${node.optJSONArray("bounds")} clickable=${node.opt("clickable")}")
                }
            }
            else -> Unit
        }
    }

    @Test fun inspectHistory() {
        val ins = InstrumentationRegistry.getInstrumentation()
        if (InstrumentationRegistry.getArguments().getString("history") != "true") return
        val app = ins.targetContext.applicationContext as MikaApp
        val rows = DockRoutes(app)
        val tasks = app.remoteTasks.localTasks
        val records = RunStore(app)
        fun report(text: String) = ins.sendStatus(0, Bundle().apply { putString("receipt", text) })
        report("helper paired=${app.phone.shellControl.paired} state=${app.phone.shellControl.status.value} live=${app.phone.hub.shell.value != null}")
        for (row in rows.all().filter { it.busy }) {
            val task = runCatching { tasks.get(row.taskId) }.getOrNull()
            report("busy ${row.id} phase=${row.phase} task=${task?.state?.javaClass?.simpleName}")
        }
        val named = tasks.list(100).filter { ("淘宝" in it.goal && "取件" in it.goal) || it.runId == "20261127-184020-6b1pdn" }
        for (task in named) {
            val events = task.runId?.let { records.events(it) }.orEmpty()
            val end = task.state as? TaskState.Succeeded
            if (task.id == "5371704b-f6cb-4016-8252-0ac7c3dbd0a4") {
                val answer = end?.answer.orEmpty()
                report("answerTokens=" + listOf("打开淘宝","淘宝","菜鸟","跳转","暂无待取包裹","待收货","没有","首页","取件码").filter { it in answer })
                report("sources=" + events.filterIsInstance<RunEvent.Sources>().sumOf { it.items.size })
                events.filterIsInstance<RunEvent.ToolResult>().forEach { e -> report("tool="+e.step+" "+e.tool+" empty="+("暂无待取包裹" in e.resultJson)+" taobao="+("com.taobao.taobao" in e.resultJson)+" cainiao="+("com.cainiao.wireless" in e.resultJson)) }
            }
            val changes = events.filterIsInstance<RunEvent.ActionTarget>().filter { it.effective }
            val kind = if ("淘宝" in task.goal) "pickup" else "filter"
            report("$kind id=${task.id} run=${task.runId} reviewed=${end?.reviewed} verified=${end?.verified} sources=${end?.sources?.size} unmatched=${end?.unconfirmed?.size} outcome=${events.filterIsInstance<RunEvent.OutcomeJudged>().lastOrNull()?.outcome} changes=${changes.size}")
            for (a in changes) {
                val endPage = a.after.texts
                report("action=${a.step} ${a.tool} incomplete=${a.incomplete} before=${a.before.packageName} pkg=${a.after.packageName} hasPickup=${endPage.any { "包裹" in it }} empty=${endPage.any { "暂无待取包裹" in it }}")
            }
            for (r in events.filterIsInstance<RunEvent.ToolResult>().filter { "暂无待取包裹" in it.resultJson })
                report("empty-step=${r.step} tool=${r.tool} hasPkg=${"com.taobao.taobao" in r.resultJson}")
            for (r in events.filterIsInstance<RunEvent.Approval>()) report("gate=${r.step} ${r.decision} payment=${r.payment}")
        }
    }
}
