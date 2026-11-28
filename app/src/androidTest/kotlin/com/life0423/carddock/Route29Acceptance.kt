package com.life0423.carddock

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.task.DockRoute
import com.wangzan101.mika.task.DockRoutes
import org.junit.Test

/** 真实设备显式传入场景才启动。只读路径、终点和回执均来自产品服务。 */
class Route29Acceptance {
    @Test fun runScenario() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val arg = InstrumentationRegistry.getArguments()
        val mode = arg.getString("scenario") ?: return
        val app = ins.targetContext.applicationContext as MikaApp
        val rows = DockRoutes(app)
        val tasks = app.remoteTasks.localTasks
        val records = RunStore(app)
        fun report(s: String) = ins.sendStatus(0, Bundle().apply { putString("receipt", s) })
        val old = arg.getString("record").orEmpty()
        val goal = arg.getString("demand") ?: "帮我在 BUFF 上找一下今天涨幅最大的枪皮"
        fun show(id: String) = app.startActivity(Intent().setClassName(app, "com.life0423.carddock.ui.MikaTasksActivity")
            .putExtra("routes", true).putExtra("routeId", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun inspect(row: DockRoute) {
            val task = tasks.get(row.taskId)
            val run = task?.runId
            val events = run?.let(records::events).orEmpty()
            if (arg.getString("summary") == "true") {
                val answer = row.result.ifBlank { row.sample }
                val replay = events.filterIsInstance<RunEvent.Replay>()
                val packages = row.route?.packages.orEmpty()
                report("id=${row.id} task=${row.taskId} run=$run phase=${row.phase} verified=${row.sampleVerified} resultAt=${row.resultAt}")
                report("evidence packages=$packages steps=${row.route?.steps?.size ?: 0} replay=${replay.count { it.matched }}/${replay.size} empty=${"暂无待取" in answer || "没有待取" in answer} brand=${"品牌" in answer} price=${"价格" in answer} scope=${"24" in answer && "100" in answer}")
                report("features=" + events.filterIsInstance<RunEvent.ToolResult>().map { it.tool }.distinct())
                var rebound = false
                val reboundSteps = mutableListOf<Int>()
                for (event in events) when (event) {
                    is RunEvent.State -> if (event.name == "replay_control_rebound") rebound = true
                    is RunEvent.Replay -> {
                        if (rebound && event.matched) reboundSteps.add(event.step)
                        rebound = false
                    }
                    else -> Unit
                }
                report("reboundSteps=$reboundSteps")
                // 原始页面和答复可能含取件码、配送地址。验收只导出生成方的非业务诊断。
                if (row.issue.isNotBlank()) report("issue=" + row.issue.replace(Regex("""\d{3,}"""), "…").take(260))
                return
            }
            for (event in events) when (event) {
                is RunEvent.ToolResult -> report("step=${event.step} tool=${event.tool} page=" + event.resultJson.take(2400))
                is RunEvent.ActionTarget -> report("target=${event.step} ${event.target?.name} after=${event.after.texts.take(8)}")
                is RunEvent.Replay, is RunEvent.AnswerCheck, is RunEvent.OutcomeJudged -> report(event.toString())
                is RunEvent.State -> when (event.name) {
                    "navigation_history" -> report("history=" + event.detail)
                    "entry_recovery" -> report("entryRecovery=" + event.detail)
                    "replay_input_read" -> report("inputRead=" + event.detail)
                    "tab_selection_visual" -> report("visualSelection=" + event.detail)
                    else -> Unit
                }
                else -> Unit
            }
            report("id=${row.id} task=${row.taskId} run=$run phase=${row.phase} verified=${row.sampleVerified} resultAt=${row.resultAt} result=${row.result} sample=${row.sample} issue=${row.issue}")
        }
        val backgroundCard = mode == "card" && arg.getString("background") == "true"
        if (!backgroundCard && mode != "scheduledPrice") {
            app.startActivity(Intent().setClassName(app, "com.life0423.carddock.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Thread.sleep(650)
        }
        if (mode == "import") {
            val row = rows.fromFree(requireNotNull(tasks.get(old)))
            report("imported=$row phase=${rows.get(row)?.phase}")
            return
        }
        if (mode == "checkpoints") {
            val row = requireNotNull(rows.get(old))
            val route = requireNotNull(row.route)
            val run = requireNotNull(tasks.get(row.taskId)?.runId)
            val proof = com.wangzan101.mika.core.replay.RouteRecorder.withReadCheckpoints(route, records.events(run), row.slots)
            report("tab=${proof.navigationTabIndex} peers=${proof.navigationPeers} terminal=${proof.terminalLabels} anonymous=${proof.verifiedAnonymousEntrance} live=${proof.requireLiveTabEvidence}")
            return
        }
        // 仅导出分段耗时与路线进度，原始页面及用户需求留在手机。
        if (mode == "publicNavigation") {
            val events = records.events(requireNotNull(arg.getString("run")))
            val allowed = setOf("取包裹", "我的快递", "待取件", "暂无待取包裹", "首页", "返回", "查快递", "跳过", "关闭", "取件码")
            for (e in events.filterIsInstance<RunEvent.ToolResult>()) {
                val data = runCatching { org.json.JSONObject(e.resultJson) }.getOrNull() ?: continue
                val page = data.optJSONObject("screen") ?: data
                val arr = page.optJSONArray("elements") ?: continue
                val labels = (0 until arr.length()).map { arr.getJSONObject(it).optString("name") }.filter { it in allowed }.distinct()
                report("public=${e.tool} pkg=${page.optString("currentApp")} labels=$labels")
            }
            val last = events.filterIsInstance<RunEvent.ModelReply>().lastOrNull { it.tool == "end_not_done" }
            if (last != null) report("stop=" + last.argsJson.orEmpty().replace(Regex("[A-Za-z0-9]{3,}"), "…").take(350))
            return
        }
        if (mode == "diagnoseProductOrPickup") {
            val events = records.events(requireNotNull(arg.getString("run")))
            for (e in events) when (e) {
                is RunEvent.ActionTarget -> report("step=${e.step} tool=${e.tool} before=${e.before.packageName} after=${e.after.packageName}")
                is RunEvent.State -> if (e.name.contains("wait") || e.name == "held" || e.name == "paused") report("hold=" + e.name) else if (e.name == "dock_price") report("hasNativePrice=true") else if(e.name == "dock_price_windows") report("checkedWindows=" + e.detail)
                is RunEvent.ModelReply -> if (e.tool == "phone_tap") {
                    val args = org.json.JSONObject(e.argsJson ?: "{}")
                    report("tap ref=${args.optString("ref")} snapshot=${args.optString("snapshotId")} declared=${args.has("target")}")
                }
                else -> Unit
            }
            return
        }
        if (mode == "captureTestCard") {
            val row = requireNotNull(rows.get(old))
            require(row.cardIntent?.price == true && row.price != null)
            val automation = ins.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            automation.executeShellCommand("am start -W -n com.life0423.carddock/.MainActivity").use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
            }
            app.startActivity(Intent().setClassName(app, "com.life0423.carddock.ui.MikaTasksActivity")
                .putExtra("routes", true).putExtra("routeId", old).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            check((1..30).any {
                val root = automation.rootInActiveWindow
                val ready = root?.packageName?.toString() == app.packageName &&
                    root.findAccessibilityNodeInfosByText("卡片详情").isNotEmpty() && root.findAccessibilityNodeInfosByText("国补领后价").isNotEmpty()
                if (!ready) Thread.sleep(250)
                ready
            }) { "未打开指定的核价卡详情" }
            val file = java.io.File(app.externalCacheDir, "cd37-test-price.png")
            file.outputStream().use { requireNotNull(automation.takeScreenshot()).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            report("capture=" + file.absolutePath)
            return
        }
        if (mode == "sourceChoice") {
            val id = rows.trial("点这张卡时显示取件码")
            val draft = requireNotNull(rows.get(id))
            check(draft.phase == DockRoute.Phase.Draft && draft.sourceChoices.size > 1)
            report("choiceSources=" + draft.sourceChoices.joinToString() + " draft=" + id)
            rows.remove(id)
            return
        }
        if (mode == "scheduledPrice") {
            val row = requireNotNull(rows.get(old))
            require(row.isCard && row.daily && row.cardIntent?.price == true && !row.busy)
            val job = app.getSystemService(android.app.job.JobScheduler::class.java)
            check(job.getPendingJob(42318) != null)
            val shell = ins.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            shell.executeShellCommand("cmd jobscheduler run -f com.life0423.carddock 42318").use {
                report(android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().take(160))
            }
            repeat(240) {
                val fresh = requireNotNull(rows.get(old))
                if (fresh.taskId != row.taskId && !fresh.busy) {
                    report("scheduled=${fresh.scheduledRun} phase=${fresh.phase} quote=${fresh.price?.display} newer=${fresh.resultAt > row.resultAt}")
                    check(fresh.scheduledRun && fresh.phase == DockRoute.Phase.Ready && fresh.resultAt > row.resultAt)
                    return
                }
                Thread.sleep(1000)
            }
            error("每日任务没有完成本轮核价")
        }
        if (mode == "removeAcceptanceRows") {
            val ids = requireNotNull(arg.getString("ids")).split(",")
            require(ids.isNotEmpty() && ids.none(String::isBlank))
            for (id in ids) require(rows.get(id)?.busy != true) { "仍有验收任务正在执行" }
            for (id in ids) rows.remove(id)
            report("removed=" + ids.count { rows.get(it) == null })
            return
        }
        if (mode == "priceState") {
            val row = requireNotNull(rows.get(old))
            report("daily=${row.daily} notify=${row.notifyDrop} scheduled=${row.scheduledRun} phase=${row.phase} verifiedAt=${row.resultAt} quote=${row.price?.display} changes=${row.priceChanges.size}")
            return
        }
        if (mode == "timings") {
            for (run in records.list()) {
                val ev = records.events(run.id)
                val start = ev.filterIsInstance<RunEvent.TaskStarted>().firstOrNull()?.atMs ?: continue
                val steps = ev.filterIsInstance<RunEvent.Replay>()
                val last = steps.lastOrNull()?.atMs ?: continue
                val end = ev.maxOf { it.atMs }
                val shown = ev.filterIsInstance<RunEvent.State>().firstOrNull { it.name == "dock_page_on_main" }
                val tools = ev.filterIsInstance<RunEvent.ToolResult>()
                val ready = ev.filterIsInstance<RunEvent.State>().firstOrNull { it.name == "dock_phone_ready" }
                report("timing id=${run.id} startMs=$start steps=${steps.count { it.matched }}/${steps.size} navigation=${last-start} tail=${end-last} shown=${shown?.atMs?.minus(start)} tool=${tools.sumOf { it.durationMs }} model=${ev.filterIsInstance<RunEvent.ModelReply>().sumOf { it.latencyMs }} total=${end-start} helper=${ready?.atMs?.minus(start)} wait=${tools.sumOf { it.waitMs }}")
            }
            return
        }
        if (mode == "priceSelection") {
            val events = records.events(requireNotNull(arg.getString("run")))
            for(e in events.filterIsInstance<RunEvent.ActionTarget>()) {
                val target = e.target
                if (target != null && "airpods" in target.name.lowercase()) report("productTarget=" + target.name.take(280))
            }
            for(e in events.filterIsInstance<RunEvent.ToolResult>()) {
                val data = runCatching { org.json.JSONObject(e.resultJson) }.getOrNull() ?: continue
                val page = data.optJSONObject("screen") ?: data
                val arr = page.optJSONArray("elements") ?: continue
                val options = (0 until arr.length()).map { arr.getJSONObject(it) }.filter {
                    "airpods" in it.optString("name").lowercase() && it.optString("name").length > 25
                }.map { it.optString("role") + " " + it.optJSONArray("bounds") + " " + it.optString("name").take(135) }
                if(options.isNotEmpty()) report("fields=${e.step} " + options.take(8))
            }
            return
        }
        if (mode == "priceFields") {
            val all = records.events(requireNotNull(arg.getString("run")))
            all.filterIsInstance<RunEvent.ModelReply>().lastOrNull { it.tool == null }?.text?.lines()
                ?.filter { listOf("商品", "规格", "价格标签").any { label -> it.trim(' ', '*', '-').startsWith(label + "：") } }
                ?.forEach { report("proposal=" + it.take(180)) }
            val events = all.filterIsInstance<RunEvent.ToolResult>()
            val latest = events.asReversed().firstNotNullOfOrNull { event ->
                val root = runCatching { org.json.JSONObject(event.resultJson) }.getOrNull() ?: return@firstNotNullOfOrNull null
                val page = root.optJSONObject("screen") ?: root
                if (page.optString("currentApp") == "com.jingdong.app.mall") page.optJSONArray("elements") else null
            } ?: error("没有京东末屏控件")
            val sample = all.filterIsInstance<RunEvent.ModelReply>().lastOrNull { it.tool == null }?.text.orEmpty()
            val elements = (0 until latest.length()).map { i ->
                val node = latest.getJSONObject(i)
                val bounds = node.optJSONArray("bounds")
                com.wangzan101.mika.core.uistate.UiElement("r$i", node.optString("role"), node.optString("name"),
                    0, 0, if (bounds == null) emptyList() else (0 until bounds.length()).map(bounds::getInt),
                    null, node.optBoolean("clickable"), node.optBoolean("editable"), node.optBoolean("secret"))
            }
            val screen = com.wangzan101.mika.core.uistate.ScreenSnapshot("receipt","com.jingdong.app.mall",elements,1000,1000)
            var why = ""
            val test = com.wangzan101.mika.core.verify.CardPrice.read(screen, sample, null) { why = it }
            report("receiptParsed=${test?.display} missing=$why nodeCount=${elements.size}")
            for (i in 0 until latest.length()) {
                val node = latest.getJSONObject(i)
                val n = node.optString("name")
                if (n.length in 1..170 && listOf("AirPods", "已选", "标准款", "元", "售价", "标价", "¥", "￥", "价格").any { it in n }
                    && listOf("地址", "配送", "收货", "送至").none { it in n }) report("productField=$n")
            }
            return
        }
        if (mode == "listEvidence") {
            val run = requireNotNull(arg.getString("run"))
            val statuses = requireNotNull(arg.getString("statuses")).split(',')
            val target = requireNotNull(arg.getString("sourcePackage"))
            val events = records.events(run).filterIsInstance<RunEvent.ToolResult>()
            val lastTap = events.indexOfLast { it.tool == "phone_tap" }
            check(lastTap >= 0)
            for (e in events.drop(lastTap)) {
                val data = runCatching { org.json.JSONObject(e.resultJson) }.getOrNull() ?: continue
                val page = data.optJSONObject("screen") ?: data
                if (page.optString("currentApp") != target) continue
                val nodes = page.optJSONArray("elements") ?: continue
                val labels = (0 until nodes.length()).mapNotNull { nodes.optJSONObject(it) }
                    .map { it.optString("name") }
                val rows = labels.mapNotNull { text ->
                    val status = statuses.singleOrNull { " $it" in text } ?: return@mapNotNull null
                    val name = text.substringBefore(" $status").trim()
                    if (name.isEmpty() || name !in labels) null else name to status
                }.distinct()
                if (rows.isNotEmpty()) report("rows=${rows.joinToString { (name, status) -> "$name=$status" }}")
            }
            return
        }
        if (mode == "routePages") {
            val row = requireNotNull(rows.get(old))
            val route = requireNotNull(row.route)
            val labels = route.steps.flatMap { it.after.texts + it.before.texts + it.target?.name.orEmpty() }.filter { it.isNotBlank() }.toSet()
            val run = requireNotNull(arg.getString("run"))
            for (e in records.events(run).filterIsInstance<RunEvent.ToolResult>()) {
                val data = runCatching { org.json.JSONObject(e.resultJson) }.getOrNull() ?: continue
                val page = data.optJSONObject("screen") ?: data
                val nodes = page.optJSONArray("elements") ?: page.optJSONArray("added") ?: continue
                val keep = (0 until nodes.length()).mapNotNull { nodes.optJSONObject(it) }.filter {
                    it.optString("name") in labels && !it.optBoolean("secret")
                }.map { n -> "${n.optString("name")}/${n.optString("role")}/${n.optJSONArray("bounds")}/${n.optBoolean("clickable")}" }
                if (keep.isNotEmpty()) report("screen=${e.step} ${e.tool} ${keep.take(32)}")
            }
            return
        }
        if (mode == "steps") {
            val row = requireNotNull(rows.get(old))
            val route = requireNotNull(row.route)
            report("scope=" + row.scope)
            tasks.get(row.id.removePrefix("free:"))?.runId?.let {
                val enriched = com.wangzan101.mika.core.replay.RouteRecorder.withReadCheckpoints(route, records.events(it), row.slots)
                report("entryButtonRow=${enriched.entryButtonRow}")
            }
            route.steps.forEachIndexed { i, step -> report("step=${i + 1} ${step.tool} before=${step.before.texts} target=${step.target} after=${step.after.texts}") }
            return
        }
        if (mode == "recover") { rows.recoverCandidate(old); inspect(requireNotNull(rows.get(old))); return }
        if (mode == "inspect") { inspect(requireNotNull(rows.get(old))); return }
        if (mode == "show") {
            ins.uiAutomation.executeShellCommand("am start -W -n com.life0423.carddock/.MainActivity").use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
            }
            ins.startActivitySync(Intent().setClassName(app, "com.life0423.carddock.ui.MikaTasksActivity")
                .putExtra("routes", true).putExtra("routeId", old).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Thread.sleep(1300)
            check(ins.uiAutomation.rootInActiveWindow?.packageName == app.packageName) { "卡坞尚未显示在主屏" }
            val output = java.io.File(app.getExternalFilesDir(null), "route29-check.png")
            output.outputStream().use { ins.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            return
        }
        if (mode == "models") {
            val providers = com.wangzan101.mika.settings.ProviderStore.get(app)
            for (role in com.wangzan101.mika.core.model.ModelRole.entries) {
                val cfg = providers.configFor(role)
                report("model=$role " + cfg?.model + " vision=" + cfg?.capabilities?.vision)
            }
            return
        }
        if (mode == "list") {
            rows.all().filter { it.saved }.forEach { report("saved=${it.id} template=${it.isTemplate} parent=${it.templateId} goal=${it.goal.take(90)}") }
            return
        }
        if (mode == "free") {
            val service = com.wangzan101.mika.core.remote.RemoteTaskService(tasks)
            val task = service.start(goal)
            val started = SystemClock.elapsedRealtime()
            repeat(400) {
                val fresh = service.get(task.id)
                if (fresh.state is TaskState.Terminal) {
                    check(fresh.state is TaskState.Succeeded) { fresh.state.toString() }
                    val row = requireNotNull(rows.get(rows.fromFree(fresh)))
                    report("elapsedMs=" + (SystemClock.elapsedRealtime() - started))
                    inspect(row)
                    return
                }
                Thread.sleep(1250)
            }
            error("自由操作超时")
        }
        val started = SystemClock.elapsedRealtime()
        val id = when(mode) {
            // 通过真实需求输入框和主按钮提交，随后仍读取同一后台回执。
            "form", "uiCard" -> {
                val card = if (mode == "uiCard") requireNotNull(rows.get(old)).also { check(it.isCard && !it.busy) } else null
                val prior = rows.all().map { it.id }.toSet()
                // 输入框测试和 Mika 的后台读屏服务在真机上须同时保持连接。
                val automation = ins.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                automation.executeShellCommand("am start -W -n com.life0423.carddock/.MainActivity").use {
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
                }
                ins.startActivitySync(Intent().setClassName(app, "com.life0423.carddock.ui.MikaTasksActivity")
                    .putExtra("routes", true).apply { if (card != null) putExtra("routeId", old) }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                fun nodes(): List<android.view.accessibility.AccessibilityNodeInfo> {
                    val root = automation.rootInActiveWindow ?: return emptyList()
                    return generateSequence(listOf(root)) { level ->
                        level.flatMap { node -> (0 until node.childCount).mapNotNull(node::getChild) }.takeIf { it.isNotEmpty() }
                    }.flatten().toList()
                }
                fun awaitNode(predicate: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean) =
                    (1..30).firstNotNullOfOrNull { nodes().firstOrNull(predicate) ?: run { Thread.sleep(200); null } }
                        ?: error("未找到需求输入控件")
                if (card == null) {
                    val field = awaitNode { it.isEditable }
                    check(field.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, goal)
                    }))
                    awaitNode { it.isEditable && it.text?.toString() == goal }
                }
                val label = if (card == null) "让 Mika 试查" else if (card.openPageOnRun) "运行并打开目标页" else "重新查询"
                val button = awaitNode { it.text?.toString() == label && it.isVisibleToUser }
                val bounds = android.graphics.Rect()
                button.getBoundsInScreen(bounds)
                automation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}").use {
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
                }
                (1..30).firstNotNullOfOrNull {
                    val submitted = if (card == null) rows.all().singleOrNull { it.id !in prior && it.goal == goal }
                    else rows.get(old)?.takeIf { it.taskId != card.taskId }
                    submitted?.id ?: run { Thread.sleep(200); null }
                } ?: error("输入提交后没有对应路线回执")
            }
            "rediscover" -> old.also { rows.restartTrial(it, requireNotNull(rows.get(it)).goal, "") }
            "cold" -> rows.trial(goal, isolated = true)
            "verify" -> old.also { rows.confirmScope(it, requireNotNull(arg.getString("scope"))) }
            "recheck" -> old.also(rows::recheck)
            "alternative" -> old.also(rows::recheckWithAlternative)
            "save" -> { report("saved=" + rows.save(old)); return }
            "create" -> rows.createAndRun(old, requireNotNull(rows.get(old)).slots)
            "card" -> old.also { rows.execute(it) }
            else -> error("未知场景")
        }
        if (!backgroundCard) show(id)
        report("started=$id isolated=${mode == "cold"}")
        var last = ""
        var lastNavigation = emptySet<String>()
        val watchPackage = arg.getString("watchPackage")
        val ui = watchPackage?.let { ins.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES) }
        repeat(480) {
            val row = requireNotNull(rows.get(id))
            val state = tasks.get(row.taskId)?.state
            if (ui != null && watchPackage != null) {
                val visible = linkedSetOf<String>()
                val words = setOf("搜索", "搜索栏", "搜索按钮", "搜索 按钮", "搜商品", "商品", "问东东", "东东", "AI搜索", "筛选",
                    "取件", "取件页面", "包裹", "暂无待取包裹", "待取", "待取件", "我的快递", "菜鸟", "待收货", "我的淘宝", "首页", "同意", "登录", "重置", "确定")
                val displays = ui.windowsOnAllDisplays
                for (d in 0 until displays.size()) for (window in displays.valueAt(d)) {
                    val root = window.root ?: continue
                    if (root.packageName?.toString() != watchPackage) continue
                    val nodes = generateSequence(listOf(root)) { level ->
                        level.flatMap { node -> (0 until node.childCount).mapNotNull(node::getChild) }.takeIf { it.isNotEmpty() }
                    }.flatten()
                    for (node in nodes) {
                        val labels = listOfNotNull(node.text?.toString()?.trim(), node.contentDescription?.toString()?.trim())
                        val known = labels.firstOrNull { it in words } ?: continue
                        val bounds = android.graphics.Rect()
                        node.getBoundsInScreen(bounds)
                        visible += "$known/${node.className.toString().substringAfterLast('.')}/${node.isClickable}/${bounds.flattenToString()}"
                    }
                }
                if (visible != lastNavigation && visible.isNotEmpty()) report("navigation=" + visible.joinToString("|"))
                lastNavigation = visible
            }
            val details = when(state) {
                is TaskState.Running -> "${state.step} ${state.note}"
                is TaskState.Failed -> state.reason
                is TaskState.WaitingInput -> state.prompt
                is TaskState.Held -> state.reason.why
                else -> state?.javaClass?.simpleName.orEmpty()
            }
            if (arg.getString("summary") == "true") {
                val phase = state?.javaClass?.simpleName.orEmpty()
                if (last != phase) { report("state=$phase"); last = phase }
            }
            else if (last != details) { report("state=$details"); last = details }
            if (!row.busy) { report("elapsedMs=" + (SystemClock.elapsedRealtime()-started)); inspect(row); return }
            Thread.sleep(1250)
        }
        error("等待超时")
    }
}
