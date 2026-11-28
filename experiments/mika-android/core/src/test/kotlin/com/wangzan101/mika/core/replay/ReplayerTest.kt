package com.wangzan101.mika.core.replay

import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionRecorder
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.knowledge.Link
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.uistate.Verification
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.ToolDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.assembly.toolDispatcher

internal fun ui(ref: String, role: String, name: String, b: List<Int>, rid: String? = null, clickable: Boolean = true, editable: Boolean = false) =
    UiElement(ref, role, name, (b[0] + b[2]) / 2, (b[1] + b[3]) / 2, b, rid, clickable, editable, false)

/** 按页面走的假手机:每页一组元素,动作按「当前页 + 点了谁」换页;记下所有输入的字。 */
internal class PagedScreen(pages: Map<String, List<UiElement>>, start: String = "launcher") : ScreenPort by FakeScreen() {
    val pages = pages.toMutableMap()
    var page = start
    var pkg: String? = null
    val typed = mutableListOf<String>()
    val taps = mutableListOf<String>()
    /** (页, 被点元素名) → 新页 */
    val links = mutableMapOf<Pair<String, String>, String>()
    val log = mutableListOf<String>()
    var failReads = false
    var protectedPage: String? = null
    private var n = 0
    private fun snap() = ScreenSnapshot("s${++n}", pkg, pages[page].orEmpty(), 1000, 1000, protected = page == protectedPage)
    override suspend fun snapshot(): ScreenSnapshot { log += "read"; if (failReads) error("unreadable"); return snap() }
    override suspend fun peek() = ScreenSnapshot("peek", pkg, pages[page].orEmpty(), 1000, 1000)
    override suspend fun openApp(packageName: String, cold: Boolean): ActionResult { pkg = packageName; page = "home"; return ActionResult.Ok() }
    override suspend fun tapRef(ref: String, snapshotId: String): ActionResult {
        val e = pages[page].orEmpty().first { it.ref == ref }
        taps += e.name
        val to = links[page to e.name] ?: return ActionResult.Ok(changed = false)
        page = to
        return ActionResult.Ok(changed = true)
    }
    override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult {
        typed += text
        return ActionResult.Ok(verification = Verification.MATCH)
    }
}

internal class Events : RunSink {
    val all = mutableListOf<RunEvent>()
    override fun event(e: RunEvent) { all += e }
    override fun media(mediaId: Int, png: ByteArray) {}
}

internal object Notes {
    val home = listOf(
        ui("@e1", "text", "备忘录", listOf(0, 0, 999, 60), clickable = false),
        ui("@e2", "button", "新建", listOf(800, 900, 999, 999), rid = "n:id/new"),
        ui("@e3", "text", "全部笔记", listOf(0, 60, 999, 120), clickable = false),
    )
    val editor = listOf(
        ui("@e1", "text", "新笔记", listOf(0, 0, 700, 60), clickable = false),
        ui("@e2", "button", "完成", listOf(800, 0, 999, 60)),
        ui("@e3", "edit", "", listOf(0, 100, 999, 900), rid = "n:id/body", editable = true),
    )
    val homeA = Anchors("com.notes", listOf("备忘录", "全部笔记"))
    val editorA = Anchors("com.notes", listOf("新笔记", "完成"))
    val steps = listOf(
        RouteStep("phone_launch_app", """{"package":"com.notes"}""", null, Anchors(null, emptyList()), homeA),
        RouteStep("phone_tap", "{}", Selector("button", "新建", "n:id/new", listOf(800, 900, 999, 999), 0, true), homeA, editorA),
        RouteStep("phone_type_text", """{"text":"{1}"}""", Selector("edit", "", "n:id/body", listOf(0, 100, 999, 900), 0, true), editorA, Anchors("com.notes", emptyList())),
    )
    fun route(steps: List<RouteStep> = this.steps) = Route("r1", "在备忘录里写{1}", 1, listOf("com.notes"), emptyMap(), steps, lastUsedMs = 0)
    fun screen() = PagedScreen(mapOf("home" to home, "editor" to editor)).apply { links["home" to "新建"] = "editor" }
}

/** 回放和循环里一样:动作经执行入口,同一份守卫、确认关卡与记录。 */
internal fun replayerOn(d: ToolDispatcher, sink: RunSink, gate: suspend () -> Unit = {}) =
    Replayer(d, ActionExecutor(d, OperationGuards(), ConfirmationGate(), gate, ActionRecorder(sink)), gate = gate, sink = sink)

class ReplayerTest {
    private fun replayer(s: ScreenPort, sink: RunSink = Events(), gate: suspend () -> Unit = {}) = toolDispatcher(s).also { it.captureTargets = true }.let { d ->
        replayerOn(d, sink, gate)
    }

    @Test fun childPageKeepsOnlyScopeLabelsActuallyReadOnItsParent() = runTest {
        val phone = Notes.screen()
        val runner = replayer(phone)
        val route = Notes.route(Notes.steps.take(2) + Notes.steps.last().copy(argsJson = """{"text":"正文"}"""))
        val outcome = runner.run(RouteChoice(route, emptyList(), "在新笔记里面输入正文，查找不存在的标题"), fromAppEntry = true)
        assertTrue(outcome.toString(), outcome is ReplayOutcome.Completed)
        assertEquals(setOf("新笔记"), runner.confirmedPageLabels.keys)
    }

    @Test fun replayChecksTheSameNamedControlWhenFreshPortRefsAreRenumbered() = runTest {
        for (changed in listOf(false, true)) {
            val phone = Notes.screen()
            val screen = object : ScreenPort by phone {
                override suspend fun peek(): ScreenSnapshot {
                    val fresh = phone.peek()
                    if (phone.page != "home") return fresh
                    return fresh.copy(elements = fresh.elements.map { element ->
                        when (element.ref) {
                            "@e2" -> element.copy(ref = "@e9", name = if (changed) "其他" else element.name)
                            "@e3" -> element.copy(ref = "@e2", name = "")
                            else -> element
                        }
                    })
                }
            }
            val dispatcher = toolDispatcher(screen)
            val events = Events()
            val browse = object : com.wangzan101.mika.core.action.ApprovalSettings {
                override fun autonomous() = false
                override val browseRoute = true
            }
            val executor = ActionExecutor(dispatcher, OperationGuards(), ConfirmationGate(), {}, ActionRecorder(events), approvals = browse)
            val replay = Replayer(dispatcher, executor, {}, events).run(RouteChoice(Notes.route(Notes.steps.take(2)), emptyList()), fromAppEntry = true)
            assertEquals("changed=$changed, $replay", !changed, replay is ReplayOutcome.Completed)
            assertEquals(if (changed) emptyList<String>() else listOf("新建"), phone.taps)
        }
    }

    @Test fun unnamedRecordedIconIsLocatedAgainOnTheLiveScreen() = runTest {
        val phone = Notes.screen()
        phone.pages["home"] = Notes.home.map { if (it.resourceId == "n:id/new") it.copy(name = "") else it }
        var tapped = false
        val screen = object : ScreenPort by phone {
            override suspend fun screenshotPng() = com.wangzan101.mika.core.vision.FRAME_A
            override suspend fun tapXY(x: Int, y: Int): ActionResult {
                tapped = true
                phone.page = "editor"
                return ActionResult.Ok(changed = true)
            }
        }
        val target = Notes.steps[1].target!!.copy(name = "", visualDescription = "右下角的筛选图标")
        val steps = listOf(Notes.steps[0], Notes.steps[1].copy(target = target))
        val route = RouteCodec.decode(RouteCodec.encode(Notes.route(steps)))
        val vision = com.wangzan101.mika.core.vision.VisionService(com.wangzan101.mika.core.vision.says(
            """{"bbox_2d":[800,900,990,990],"seen":"筛选"}""", "是"), com.wangzan101.mika.core.vision.JvmTestImageOps)
        val dispatcher = toolDispatcher(screen, vision = vision)
        val outcome = replayerOn(dispatcher, Events()).run(RouteChoice(route, emptyList()), fromAppEntry = true)
        assertTrue(outcome.toString(), outcome is ReplayOutcome.Completed)
        assertTrue(tapped)
    }

    @Test fun missingOrAmbiguousControlRequiresCurrentPixelsAndExpectedDestination() = runTest {
        for (scenario in listOf("missing", "emptyTree", "ambiguous", "wrongDestination", "anonymousWrongPage", "wrongBox", "unsafeLabel",
            "checkpoint", "checkpointWrongPage", "checkpointNoPixels", "checkpointLongGoal", "checkpointLongContext")) {
            val phone = Notes.screen()
            val b = listOf(800, 900, 999, 999)
            val checkpoint = scenario.startsWith("checkpoint")
            val duplicates = when {
                scenario == "ambiguous" -> listOf(ui("@e7", "button", "筛选", b), ui("@e8", "button", "筛选", listOf(0, 500, 100, 600)))
                checkpoint -> listOf(ui("@e7", "button", "筛选", b))
                else -> emptyList()
            }
            phone.pages["home"] = if (scenario in setOf("emptyTree", "checkpointLongContext")) emptyList()
                else Notes.home.filterNot { it.resourceId == "n:id/new" } + duplicates
            if (scenario == "anonymousWrongPage") phone.pages["home"] = listOf(ui("@e4", "Layout", "", b))
            var taps = 0
            val screen = object : ScreenPort by phone {
                override suspend fun screenshotPng() = com.wangzan101.mika.core.vision.FRAME_A
                override suspend fun tapXY(x: Int, y: Int): ActionResult {
                    taps++
                    if (scenario !in setOf("wrongDestination", "anonymousWrongPage", "checkpointWrongPage")) phone.page = "editor"
                    return ActionResult.Ok(changed = true)
                }
            }
            val description = when (scenario) {
                "checkpointLongGoal" -> "图".repeat(120) + "蓝色"
                "checkpointLongContext" -> "图".repeat(110) + "蓝色"
                else -> "右下方的筛选按钮"
            }
            val sel = Selector("button", "筛选", null, b, 0, uniqueAtRecord = true, visualDescription = description, requireVisualCheck = checkpoint)
            val recorded = if (scenario == "anonymousWrongPage") sel.copy(role = "Layout", name = "") else sel
            val route = Notes.route(listOf(Notes.steps.first(), Notes.steps[1].copy(target = recorded)))
            val box = if (scenario == "wrongBox") "[100,150,200,200]" else "[800,900,990,990]"
            val seen = if (scenario == "unsafeLabel") "立即支付" else "筛选"
            val imageReply = if (scenario == "checkpointNoPixels") "null" else """{"bbox_2d":$box,"seen":"$seen"}"""
            val vision = com.wangzan101.mika.core.vision.VisionService(com.wangzan101.mika.core.vision.says(
                imageReply, "是"), com.wangzan101.mika.core.vision.JvmTestImageOps)
            val dispatcher = toolDispatcher(screen, vision = vision)
            val events = Events()
            val browse = object : com.wangzan101.mika.core.action.ApprovalSettings {
                override fun autonomous() = false
                override val browseRoute = true
            }
            val executor = ActionExecutor(dispatcher, OperationGuards(), ConfirmationGate(), {}, ActionRecorder(events), approvals = browse)
            val runner = Replayer(dispatcher, executor, {}, events)
            val replay = runner.run(RouteChoice(route, emptyList()), fromAppEntry = true)
            val passed = scenario in setOf("missing", "emptyTree", "ambiguous", "checkpoint")
            assertEquals("$scenario $replay", passed, replay is ReplayOutcome.Completed)
            assertEquals(scenario, if (passed || scenario in setOf("wrongDestination", "anonymousWrongPage", "checkpointWrongPage")) 1 else 0, taps)
            assertEquals(scenario, passed, events.all.filterIsInstance<RunEvent.State>().any { it.name == "replay_visual_fallback" })
            assertEquals(scenario, scenario == "checkpoint", runner.checkedEntryReads.isNotEmpty())
            if (scenario == "checkpoint") {
                val observed = dispatcher.evidence(fresh = false).ledger.all.single { it.replayEntrySource == "vision" }
                val recorded = RunEvent.Observed.of(observed)
                assertEquals("fact", recorded.kind)
                assertTrue(recorded.inputs == null && recorded.observation().replayEntrySource == "vision")
                // 答复只引用结果值，且模型又补读了面板，独立核查仍需对照前一屏的图标。
                dispatcher.noteReplayRead("com.notes", "共有2个选项")
                val judge = com.wangzan101.mika.core.vision.says("""{"verdict":"pass","checks":[{"criterion":"查询","status":"met"}],"reason":"ok"}""")
                val goal = "核实入口的形状并读取数量"
                com.wangzan101.mika.core.verify.Verifier(judge, null).verify(goal,
                    com.wangzan101.mika.core.verify.Criteria.of(goal, false), "共有2个选项", dispatcher.evidence(fresh = false))
                val request = judge.requests.single().flatMap { it.parts }.filterIsInstance<com.wangzan101.mika.core.model.Part.Text>().single().text
                assertTrue(listOf("已通过框选复核", seen, "新笔记", "完成").all { it in request })
            }
        }
    }

    @Test fun explicitAlternativeUsesAFreshTextControlOnlyAfterFailedPixelsAndTwoNewLabels() = runTest {
        val permission = "看图找不到时，再从当前页用可核实的只读入口继续。"
        for (scenario in listOf("nested1260", "separate1260", "allowed", "scopeAllowed", "strict", "duplicate", "moved", "oneLabel")) {
            val phone = Notes.screen()
            val physical = scenario.endsWith("1260")
            val button = if (physical) Notes.home[1].copy(name = "筛选", bounds = listOf(831, 205, 955, 255),
                centerX = 1126, centerY = 645) else Notes.home[1].copy(name = "筛选")
            phone.pages["home"] = listOf(Notes.home.first(), button, Notes.home.last())
            if (physical) {
                val label = if (scenario == "nested1260") button.copy(role = "TextView", ref = "@e8", clickable = false,
                    bounds = listOf(831, 205, 907, 255), centerX = 1096, centerY = 645)
                else button.copy(role = "TextView", ref = "@e8", clickable = false, bounds = listOf(135, 205, 211, 255),
                    centerX = 218, centerY = 645)
                phone.pages["home"] = phone.pages.getValue("home") + label
            }
            if (scenario == "duplicate") phone.pages["home"] = phone.pages.getValue("home") + button.copy(ref = "@e9", bounds = listOf(0, 300, 99, 399))
            if (scenario == "oneLabel") phone.pages["editor"] = Notes.editor.take(1)
            phone.links["home" to "筛选"] = "editor"
            val model = com.wangzan101.mika.core.vision.says("""{"bbox_2d":[800,900,990,990],"seen":"筛选"}""", "否")
            val screen = object : ScreenPort by phone {
                override suspend fun screenshotPng() = com.wangzan101.mika.core.vision.FRAME_A
                override suspend fun tapXY(x: Int, y: Int): ActionResult = error("未核实的坐标不可点击")
                override suspend fun snapshot(): ScreenSnapshot = phone.snapshot().let { s ->
                    if (scenario == "moved" && model.requests.isNotEmpty()) s.copy(elements = listOf(Notes.home.first()))
                    else if (physical) s.copy(width = 1260, height = 2800) else s
                }
            }
            val dispatcher = toolDispatcher(screen, vision = com.wangzan101.mika.core.vision.VisionService(model, com.wangzan101.mika.core.vision.JvmTestImageOps))
            val target = Notes.steps[1].target!!.copy(name = "筛选", bounds = button.bounds, requireVisualCheck = true, visualDescription = "漏斗图标")
            val route = Notes.route(listOf(Notes.steps.first(), Notes.steps[1].copy(target = target))).copy(template = "请核实图标。$permission")
            val runner = replayerOn(dispatcher, Events())
            val scope = when (scenario) {
                "strict" -> "无法核实就停止。"
                "scopeAllowed" -> "先用本次截图核实图标。若找不到图标，允许从可核实的只读入口继续。"
                else -> route.template
            }
            val result = runner.run(RouteChoice(route, emptyList(), scope), fromAppEntry = true)
            val allowed = scenario in setOf("allowed", "scopeAllowed", "nested1260")
            assertEquals("$scenario $result", allowed, result is ReplayOutcome.Completed)
            assertEquals(scenario, if (allowed || scenario == "oneLabel") listOf("筛选") else emptyList(), phone.taps)
            if (allowed) {
                val proof = runner.checkedEntryReads.single()
                assertEquals("elements", (proof["source"] as kotlinx.serialization.json.JsonPrimitive).content)
                assertTrue(listOf("图形未核实", "筛选", "新笔记", "完成").all { it in proof.toString() })
            } else {
                assertTrue(runner.checkedEntryReads.isEmpty())
                val reason = when (scenario) {
                    "strict" -> "本次口径未明确允许"
                    "duplicate" -> "首次读屏未能唯一定位"
                    "moved" -> "重读时原页面标识不足"
                    "oneLabel" -> "文字备用入口未出现两个预期的新页标"
                    "separate1260" -> "其他同名节点位于入口区域以外"
                    else -> error(scenario)
                }
                assertTrue("$scenario $result", reason in (result as ReplayOutcome.Diverged).expected)
            }
        }
    }

    @Test fun officialEmptyEndpointMustBeRereadWithTheRecordedPageLabel() = runTest {
        for (scenario in listOf("current", "unrelated", "login", "changed")) {
            val phone = Notes.screen()
            val empty = ui("@e9", "text", "暂无待取包裹", listOf(200, 400, 800, 500))
            val header = ui("@e8", "text", if (scenario == "unrelated") "消息" else "我的包裹", listOf(0, 30, 800, 70))
            phone.pages["home"] = listOf(header, empty) + if (scenario == "login") listOf(header.copy(name = "请先登录")) else emptyList()
            val before = Notes.homeA
            val after = Anchors("com.notes", listOf("我的包裹", "暂无待取包裹"))
            val route = Notes.route(listOf(Notes.steps[0], Notes.steps[1].copy(before = before, after = after))).copy(template = "查看取件码", slots = 0)
            val events = Events()
            var checked = 0
            val screen = object : ScreenPort by phone {
                override suspend fun snapshot(): ScreenSnapshot {
                    val snap = phone.snapshot()
                    if (events.all.filterIsInstance<RunEvent.Replay>().any { it.step == 1 && it.matched } && ++checked > 1 && scenario == "changed")
                        return snap.copy(elements = listOf(header))
                    return snap
                }
            }
            val out = replayer(screen, events).run(RouteChoice(route, emptyList()), fromAppEntry = true)
            assertEquals("$scenario $out", scenario == "current", out is ReplayOutcome.Completed)
            assertTrue(phone.taps.isEmpty())
            if (scenario == "current") assertTrue(events.all.filterIsInstance<RunEvent.Replay>().last().skipped)
        }
    }

    @Test fun changingSnapshotAfterConfirmedLaunchCannotDriveATap() = runTest {
        val phone = Notes.screen()
        val events = Events()
        val refreshing = object : ScreenPort by phone {
            override suspend fun snapshot(): ScreenSnapshot = phone.snapshot().copy(
                changedDuringRead = events.all.any { it is RunEvent.Replay && it.step == 1 && it.matched })
        }
        val out = replayer(refreshing, events).run(RouteChoice(Notes.route(Notes.steps.take(2)), emptyList()), fromAppEntry = true)
        assertTrue(out is ReplayOutcome.Diverged && out.done == 1)
        assertTrue(phone.taps.isEmpty())
    }

    @Test fun singlePackageCardRejectsForeignFinalPageEvenWhenTheTapChangedTheScreen() = runTest {
        for (reportedByAction in listOf(true, false)) {
            val phone = Notes.screen()
            val switched = object : ScreenPort by phone {
                override suspend fun tapRef(ref: String, snapshotId: String): ActionResult {
                    phone.tapRef(ref, snapshotId)
                    return ActionResult.Ok(changed = true, appSwitched = if (reportedByAction) "com.calculator" else null,
                        switchedByTarget = reportedByAction)
                }
                override suspend fun snapshot(): ScreenSnapshot = phone.snapshot().let {
                    if (!reportedByAction && phone.page == "editor") it.copy(packageName = "com.calculator") else it
                }
            }
            val replay = replayer(switched)
            val out = replay.run(RouteChoice(Notes.route(Notes.steps.take(2)), emptyList()), fromAppEntry = true)
            assertTrue(out.toString(), out is ReplayOutcome.Diverged && out.executed && out.done == 1)
            assertEquals(listOf(0), replay.matchedSteps)
            assertEquals(listOf("新建"), phone.taps)
        }
    }

    @Test fun restoredListingReturnsToHomeAndReplaysTheCurrentQueryOnlyOnASafeOwnedPage() = runTest {
        val listing = Anchors("com.notes", listOf("商品列表", "返回"))
        val filter = ui("@e41", "Button", "筛选", listOf(800, 150, 990, 200))
        val back = ui("@e42", "Button", "返回", listOf(10, 50, 110, 100))
        val controls = listOf(filter, back, ui("@e43", "TextView", "商品列表", listOf(230, 50, 500, 100)))
        val submit = RouteStep("phone_tap", "{}", Selector("button", "完成", null, Notes.editor[1].bounds, 0, true), Notes.editorA, listing)
        val openFilter = RouteStep("phone_tap", "{}", Selector(filter.role, filter.name, null, filter.bounds, 0, true), listing, listing)
        for (case in listOf("restored", "restored without entry labels", "no back", "foreign", "edited", "confirmation", "unchanged")) {
            val steps = if (case == "restored without entry labels")
                listOf(Notes.steps[0], Notes.steps[1].copy(before = Anchors("com.notes", emptyList())), Notes.steps[2])
            else Notes.steps
            val route = Notes.route(steps + submit + openFilter)
            val phone = Notes.screen()
            val events = Events()
            val extra = when (case) {
                "edited" -> listOf(Notes.editor[2].copy(name = "还未保存"))
                "confirmation" -> listOf(ui("@e44", "Button", "取消", listOf(400, 500, 510, 560)))
                else -> emptyList()
            }
            phone.pages["listing"] = controls.filter { case != "no back" || it != back } + extra
            phone.pages["filter"] = listOf(filter)
            phone.links["editor" to "完成"] = "listing"
            phone.links["listing" to "筛选"] = "filter"
            var backs = 0
            val screen = object : ScreenPort by phone {
                override suspend fun openApp(packageName: String, cold: Boolean): ActionResult {
                    phone.pkg = if (case == "foreign") "com.other" else packageName
                    phone.page = "listing"
                    return ActionResult.Ok()
                }
                override suspend fun back(): ActionResult {
                    backs++
                    if (case != "unchanged") phone.page = "home"
                    return ActionResult.Ok(changed = true)
                }
            }
            val out = replayer(screen, events).run(RouteChoice(route, listOf("新的搜索词")), fromAppEntry = true)
            if (case.startsWith("restored")) {
                assertEquals(ReplayOutcome.Completed(5), out)
                assertEquals(1, backs)
                assertEquals(listOf("新的搜索词"), phone.typed)
                assertEquals(listOf("新建", "完成", "筛选"), phone.taps)
                assertTrue(events.all.any { it is RunEvent.State && it.name == "entry_recovery" })
            } else {
                assertTrue("$case: $out", out is ReplayOutcome.Diverged)
                assertEquals(case, if (case == "unchanged") 1 else 0, backs)
                assertTrue(phone.taps.isEmpty() && phone.typed.isEmpty())
            }
        }
    }

    @Test fun restoresTabbedChildThroughItsRecordedToolbarPages() = runTest {
        val pkg = "com.notes"
        var serial = 0
        fun item(text: String, x: Int, y: Int = 20, role: String = "text") =
            ui("@e${++serial}", role, text, listOf(x, y, x + 90, y + 40))
        val back = item("返回按钮", 10).copy(clickable = false)
        val shelf = item("书库", 120, 300)
        val book = item("我的笔记", 120, 300)
        val chapters = item("目录/索引", 240, 300)
        val pages = mapOf("home" to listOf(item("首页", 360), shelf), "shelf" to listOf(back, item("书架", 420), item("管理", 810), book),
            "book" to listOf(back, item("书名", 420), item("客服", 810), chapters),
            "end" to listOf(back, item("目录", 350, role = "RadioButton"), item("索引", 490, role = "RadioButton")))
        fun marks(page: String) = Anchors(pkg, pages.getValue(page).filter { it.bounds[1] == 20 }.map { it.name })
        fun step(e: UiElement, a: String, b: String) = RouteStep("phone_tap", "{}", Selector(e.role, e.name, null, e.bounds, 0, true), marks(a), marks(b))
        val route = Notes.route(listOf(Notes.steps.first().copy(after = marks("home")),
            step(shelf, "home", "shelf"), step(book, "shelf", "book"), step(chapters, "book", "end")))
        val phone = PagedScreen(pages)
        phone.links.putAll(mapOf(("home" to shelf.name) to "shelf", ("shelf" to book.name) to "book", ("book" to chapters.name) to "end"))
        val returned = mutableListOf<String>()
        val screen = object : ScreenPort by phone {
            override suspend fun openApp(packageName: String, cold: Boolean): ActionResult {
                phone.pkg = pkg; phone.page = "end"
                return ActionResult.Ok()
            }
            override suspend fun back(): ActionResult {
                returned.add(phone.page)
                phone.page = mapOf("end" to "book", "book" to "shelf", "shelf" to "home").getValue(phone.page)
                return ActionResult.Ok(changed = true)
            }
        }
        val outcome = replayer(screen).run(RouteChoice(route, emptyList()), fromAppEntry = true)
        assertTrue(outcome.toString(), outcome is ReplayOutcome.Completed)
        assertEquals(listOf("end", "book", "shelf"), returned)
    }

    @Test fun restoredDetailTitleReturnsThroughTheParentAndReplaysBothNavigationTaps() = runTest {
        val pkg = "com.taobao.taobao"
        val station = ui("@e10", "FrameLayout", "菜鸟驿站", listOf(716, 137, 878, 214))
        val entry = ui("@e11", "View", "身份码", listOf(774, 160, 953, 178))
        val back = ui("@back", "FrameLayout", "返回", listOf(33, 53, 108, 87))
        val title = ui("@title", "TextView", "身份码", listOf(154, 55, 321, 86), clickable = false)
        val web = ui("@web", "WebView", "身份码", listOf(0, 0, 999, 999), clickable = false)
        val homeLabels = listOf("搜索栏", "搜索", "首页")
        val parentLabels = listOf("待取件", "返回", "菜鸟驿站")
        fun tap(e: UiElement, before: List<String>, after: List<String>) = RouteStep("phone_tap", "{}",
            Selector(e.role, e.name, null, e.bounds, 0, true), Anchors(pkg, before), Anchors(pkg, after))
        val launch = Notes.steps[0].copy(argsJson = """{"package":"$pkg"}""", after = Anchors(pkg, listOf("菜鸟驿站", "搜索栏", "搜索")))
        val route = Notes.route(listOf(launch, tap(station, homeLabels, listOf("身份码", "待取件", "返回")),
            tap(entry, parentLabels, emptyList()))).copy(packages = listOf(pkg), template = "打开身份码")
        for (case in listOf("detail", "clickable", "duplicate", "different row", "same-name entry", "form", "protected", "unknown parent")) {
            val page = when (case) {
                "clickable" -> listOf(title.copy(clickable = true))
                "duplicate" -> listOf(title, title.copy(ref = "@other", bounds = listOf(360, 55, 510, 86)))
                "different row" -> listOf(title.copy(bounds = listOf(154, 150, 321, 186)))
                "same-name entry" -> listOf(title, entry)
                "form" -> listOf(title, Notes.editor.last())
                else -> listOf(title)
            }
            val phone = PagedScreen(mapOf(
                "home" to listOf(station) + homeLabels.mapIndexed { i, label -> Notes.home[0].copy(ref = "@h$i", name = label) },
                "parent" to listOf(back, entry, ui("@e12", "TextView", "身份码", listOf(805, 160, 921, 178))) +
                    if (case == "unknown parent") emptyList() else listOf(Notes.home[0].copy(name = "待取件"), web.copy(name = "菜鸟驿站")),
                "detail" to listOf(back, web) + page))
            phone.links["home" to station.name] = "parent"
            phone.links["parent" to entry.name] = "detail"
            if (case == "protected") phone.protectedPage = "detail"
            val returnedFrom = mutableListOf<String>()
            val screen = object : ScreenPort by phone {
                override suspend fun openApp(packageName: String, cold: Boolean): ActionResult {
                    phone.pkg = packageName
                    phone.page = "detail"
                    return ActionResult.Ok()
                }
                override suspend fun back(): ActionResult {
                    returnedFrom += phone.page
                    phone.page = if (phone.page == "detail") "parent" else "home"
                    return ActionResult.Ok(changed = true)
                }
            }
            val replay = replayer(screen)
            val out = replay.run(RouteChoice(route, emptyList()), fromAppEntry = true)
            if (case == "detail") {
                assertEquals(ReplayOutcome.Completed(3), out)
                assertEquals(listOf("detail", "parent"), returnedFrom)
                assertEquals(listOf("菜鸟驿站", "身份码"), phone.taps)
                assertEquals(listOf(0, 1, 2), replay.matchedSteps)
            } else {
                assertTrue("$case $out", out is ReplayOutcome.Diverged)
                assertEquals(case, if (case == "unknown parent") listOf("detail") else emptyList(), returnedFrom)
                assertTrue(case, phone.taps.isEmpty())
            }
        }
    }

    @Test fun entryWaitsForNamedTargetWhenLaunchInitiallyOnlyShowsSplash() = runTest {
        val phone = Notes.screen()
        var reads = 0
        val delayed = object : ScreenPort by phone {
            override suspend fun snapshot(): ScreenSnapshot {
                val current = phone.snapshot()
                if (phone.page == "home" && ++reads < 10)
                    return current.copy(elements = listOf(ui("@splash", "button", "跳过", listOf(900, 0, 999, 60))))
                return current
            }
        }
        val tap = Notes.steps[1].copy(before = Anchors("com.notes", emptyList()))
        val route = Notes.route(listOf(Notes.steps[0], tap))
        assertEquals(ReplayOutcome.Completed(2), replayer(delayed).run(RouteChoice(route, emptyList()), fromAppEntry = true))
        assertEquals(listOf("新建"), phone.taps)
    }

    @Test fun searchEditorWaitsForItsInputTreeAfterNavigation() = runTest {
        val phone = Notes.screen()
        var editorReads = 0
        val delayed = object : ScreenPort by phone {
            override suspend fun snapshot(): ScreenSnapshot {
                val current = phone.snapshot()
                if (phone.page == "editor" && ++editorReads < 8) return current.copy(elements = emptyList())
                return current
            }
        }
        val out = replayer(delayed).run(RouteChoice(Notes.route(), listOf("茶杯")), fromAppEntry = true)
        assertEquals(ReplayOutcome.Completed(3), out)
        assertEquals(listOf("茶杯"), phone.typed)
    }

    @Test fun entryWaitsForReadableWindowWithoutLaunchingTwice() = runTest {
        for (arrives in listOf(true, false)) {
            val phone = Notes.screen()
            var reads = 0
            var launches = 0
            val delayed = object : ScreenPort by phone {
                override suspend fun openApp(packageName: String, cold: Boolean): ActionResult {
                    launches++
                    return phone.openApp(packageName, cold)
                }
                override suspend fun snapshot(): ScreenSnapshot {
                    val actual = phone.snapshot()
                    reads++
                    return if (!arrives || reads < 5) actual.copy(packageName = null, elements = emptyList(), hasWindow = false) else actual
                }
            }
            val out = replayer(delayed).run(RouteChoice(Notes.route(Notes.steps.take(2)), emptyList()), fromAppEntry = true)
            assertEquals(1, launches)
            if (arrives) {
                assertEquals(ReplayOutcome.Completed(2), out)
                assertEquals(listOf("新建"), phone.taps)
            } else {
                assertTrue(out is ReplayOutcome.Diverged && out.expected.contains("当前应用 未识别"))
                assertTrue(phone.taps.isEmpty())
            }
        }
    }

    @Test fun resumesFromTheObservedTabStripUsingNormalizedBounds() = runTest {
        // Android 的中心是物理像素，bounds 是千分坐标，真机两者不同。
        val tabs = listOf("概览", "排行", "趋势").mapIndexed { i, name ->
            ui("@e${i + 1}", "tab", name, listOf(i * 150, 50, i * 150 + 140, 110)).copy(centerY = 225)
        }
        val rank = tabs + listOf(ui("@e4", "text", "涨幅", listOf(10, 140, 90, 175)), ui("@e5", "text", "百分比", listOf(100, 140, 190, 175)))
        val screen = PagedScreen(mapOf("home" to tabs, "rank" to rank)).apply { links["home" to "排行"] = "rank" }
        val target = Selector("tab", "排行", null, tabs[1].bounds, 0, true)
        val anchor = Anchors("com.notes", emptyList())
        val last = RouteStep("phone_tap", "{}", target, anchor, anchor)
        val route = Notes.route(listOf(Notes.steps[0], last.copy(target = target.copy(name = "图表")), last))
            .copy(slots = 0, navigationPeers = setOf("概览", "趋势"), terminalLabels = setOf("涨幅", "百分比"))
        val replay = replayer(screen)
        assertEquals(ReplayOutcome.Completed(2, true), replay.run(RouteChoice(route, emptyList()), fromAppEntry = true))
        assertEquals(listOf(0, 2), replay.matchedSteps)
        assertEquals(listOf("排行"), screen.taps)
    }

    @Test fun resumedTabKeepsEarlierParameterAndTrailingAppLaunch() = runTest {
        val tabs = listOf("概览", "排行", "趋势").mapIndexed { i, label ->
            ui("@e${i + 1}", "tab", label, listOf(i * 150, 50, i * 150 + 140, 110))
        }
        val entry = ui("@e8", "button", "本店", listOf(10, 800, 150, 850))
        val home = tabs + entry
        val results = tabs + listOf(ui("@e4", "text", "涨幅", listOf(10, 140, 90, 175)),
            ui("@e5", "text", "百分比", listOf(100, 140, 190, 175)))
        val anchor = Anchors("com.notes", emptyList())
        val tab = RouteStep("phone_tap", "{}", Selector("tab", "排行", null, tabs[1].bounds, 0, true), anchor, anchor)
        val parameter = tab.copy(target = Selector("button", "{1}", null, entry.bounds, 0, true))
        val anonymous = tab.copy(target = Selector("group", "", null, listOf(10, 250, 150, 350), 2))
        for (missingScope in listOf(false, true)) {
            val screen = PagedScreen(mapOf("home" to home, "strip" to tabs,
                "rank" to if (missingScope) results.dropLast(1) else results)).apply {
                links["home" to "本店"] = "strip"
                links["strip" to "排行"] = "rank"
            }
            val reopen = Notes.steps[0].copy(argsJson = """{"package":"com.notes","reason":"回到应用核对结果"}""")
            val route = Notes.route(listOf(Notes.steps[0], parameter, anonymous, tab, reopen))
                .copy(navigationTabIndex = 3, verifiedAnonymousEntrance = true, navigationPeers = setOf("概览", "趋势"), terminalLabels = setOf("涨幅", "百分比"))
            val sameApp = object : ScreenPort by screen {
                override suspend fun openApp(packageName: String, cold: Boolean) =
                    if (screen.pkg == packageName) ActionResult.Ok() else screen.openApp(packageName, cold)
            }
            val replay = replayer(sameApp)
            val end = replay.run(RouteChoice(route, listOf("本店")), fromAppEntry = true)
            assertEquals(listOf("本店", "排行"), screen.taps)
            if (missingScope) assertTrue(end is ReplayOutcome.Diverged)
            else assertEquals(listOf(0, 1, 3, 4), replay.matchedSteps)
        }
    }

    @Test fun aNoOpTabWithSharedLabelsNeedsAnExplicitSelection() = runTest {
        val labels = setOf("列表", "分类")
        val tabs = listOf("概览", "排行", "趋势").mapIndexed { i, label ->
            ui("@e${i + 1}", "tab", label, listOf(i * 150, 50, i * 150 + 140, 110))
        }
        val shared = labels.mapIndexed { i, s -> ui("@e${i + 4}", "text", s, listOf(i * 150, 150, i * 150 + 140, 190)) }
        val anchor = Anchors("com.notes", emptyList())
        val tab = RouteStep("phone_tap", "{}", Selector("tab", "排行", null, tabs[1].bounds, 0, true), anchor, anchor)
        val entry = tab.copy(target = Selector("ViewGroup", "", null, listOf(10, 250, 150, 350), 2))
        val route = Notes.route(listOf(Notes.steps[0], entry, tab)).copy(slots = 0, navigationTabIndex = 2,
            terminalLabels = labels, navigationPeers = setOf("概览", "趋势"), requireLiveTabEvidence = true)
        for (selected in listOf(false, true)) {
            val items = tabs.map { if (selected && it.name == "排行") it.copy(states = setOf("selected")) else it } + shared
            val screen = PagedScreen(mapOf("home" to items))
            val outcome = replayer(screen).run(RouteChoice(route, emptyList()), fromAppEntry = true)
            assertEquals(listOf("排行"), screen.taps)
            assertEquals(selected, outcome is ReplayOutcome.Completed)
            if (!selected) {
                for ((box, expected) in listOf("[150,50,290,110]" to true, "[0,50,100,110]" to false)) {
                    val visualScreen = object : ScreenPort by screen {
                        override suspend fun screenshotPng() = com.wangzan101.mika.core.vision.FRAME_A
                    }
                    val vision = com.wangzan101.mika.core.vision.VisionService(
                        com.wangzan101.mika.core.vision.says("""{"bbox_2d":$box,"seen":"蓝色选中下划线"}""", "是"),
                        com.wangzan101.mika.core.vision.JvmTestImageOps)
                    val checked = replayerOn(toolDispatcher(visualScreen, vision = vision), Events())
                        .run(RouteChoice(route, emptyList()), fromAppEntry = true)
                    assertEquals(checked.toString(), expected, checked is ReplayOutcome.Completed)
                }
            }
        }
    }

    @Test fun verifiedCheckpointNeedsTheOriginalContainerAndStableTabReceipt() {
        val anchor = Anchors("com.notes", emptyList())
        val box = listOf(20, 200, 300, 400)
        val entry = Selector("ViewGroup", "", null, box, 2)
        val tab = Selector("tab", "排行", null, listOf(200, 50, 280, 110), 0, true)
        val tap = RouteStep("phone_tap", "{}", tab, anchor, anchor)
        val route = Notes.route(listOf(Notes.steps[0], tap.copy(target = entry), tap))
        val actions = route.steps.mapIndexed { i, s ->
            RunEvent.ActionTarget(i.toLong(), i, s.tool, s.argsJson, s.target, s.before, s.after, true, "c$i")
        }
        val replay = route.steps.indices.map { i -> RunEvent.Replay(i.toLong(), route.id, i + 1, 3, true, "") }
        val end = listOf(RunEvent.TaskEnded(4, "succeeded", "市场「上层栏目」榜单「涨幅」「百分比」"),
            RunEvent.OutcomeJudged(5, "done", "", null))
        fun frame(i: Int, items: String) = RunEvent.ToolResult(i.toLong(), i, route.steps[i].tool,
            """{"screen":{"currentApp":"com.notes","full":true,"elements":[$items]}}""", true, null, callId = "c$i")
        fun node(role: String, label: String, bounds: List<Int>) =
            """{"role":"$role","name":"$label","bounds":$bounds}"""
        val home = frame(0, node("ViewGroup", "行情 今日变动 20%", box))
        val page = frame(2, listOf(node("tab", "上层栏目", listOf(200, 0, 280, 45)),
            node("tab", "排行", tab.bounds), node("tab", "行情", listOf(80, 50, 160, 110)),
            node("tab", "历史", listOf(320, 50, 400, 110)), node("text", "涨幅", listOf(10, 150, 60, 190)),
            node("text", "百分比", listOf(80, 150, 130, 190))).joinToString())
        val events = actions + replay + end
        fun derive(homeFrame: RunEvent.ToolResult, tabFrame: RunEvent.ToolResult) =
            RouteRecorder.withReadCheckpoints(route, events + homeFrame + tabFrame, listOf("本店"))
        val verified = derive(home, page)
        assertTrue(verified.verifiedAnonymousEntrance && verified.requireLiveTabEvidence)
        assertEquals(setOf("涨幅", "百分比"), verified.terminalLabels)
        assertEquals(route, derive(home.copy(resultJson = home.resultJson.replace("行情 今日变动", "本店价格")), page))
        assertEquals(route, derive(home, page.copy(callId = "unrelated")))
        assertEquals(route, derive(home, page.copy(resultJson = page.resultJson.replace("\"full\":true", "\"full\":true,\"truncated\":true"))))
    }

    @Test fun backRecoveryUsesTheOriginalButtonRowAndExecutesEveryParameterizedAction() = runTest {
        val empty = Anchors("com.notes", emptyList())
        val detail = Anchors("com.notes", listOf("详情", "返回上一级"))
        val back = RouteStep("phone_back", "{}", null, detail, empty)
        val tap = Notes.steps[1].copy(target = Notes.steps[1].target!!.copy(name = "{1}"), before = empty)
        val type = Notes.steps[2].copy(argsJson = """{"text":"{2}"}""")
        val legacy = Notes.route(listOf(Notes.steps[0], back, back, tap, type)).copy(slots = 2)
        val original = legacy.steps.mapIndexed { i, step ->
            RunEvent.ActionTarget(i.toLong(), i, step.tool, step.argsJson, if (i == 3) Notes.steps[1].target else step.target,
                step.before, step.after, true, callId = "c$i")
        }
        val tabs = listOf("概览", "发现", "分类").mapIndexed { i, name ->
            ui("@t$i", "Button", name, listOf(i * 200, 120, i * 200 + 150, 180))
        }
        val elements = tabs.joinToString { """{"role":"Button","name":"${it.name}","bounds":${it.bounds}}""" }
        val entrance = """{"role":"button","name":"新建","resourceId":"n:id/new","bounds":[800,900,999,999]}"""
        val page = """{"ok":true,"screen":{"full":true,"currentApp":"com.notes","elements":[$elements,$entrance]}}"""
        val result = RunEvent.ToolResult(2, 2, back.tool, page, true, null, callId = "c2")
        val slots = listOf("新建", "本次输入")
        val route = RouteRecorder.withReadCheckpoints(legacy, original + result, slots)
        assertEquals(tabs.map { it.name }, route.entryButtonRow)
        assertEquals(legacy.steps, route.steps)
        assertEquals(legacy, RouteRecorder.withReadCheckpoints(legacy, original + result, listOf("别的入口", "旧输入")))
        assertEquals(legacy, RouteRecorder.withReadCheckpoints(legacy, original + result.copy(callId = "无关联回执"), slots))
        for (unproven in listOf(page.replace(",$entrance", ""), page.replace(entrance, "$entrance,$entrance"))) {
            assertEquals(legacy, RouteRecorder.withReadCheckpoints(legacy, original + result.copy(resultJson = unproven), slots))
        }
        for (scenario in listOf("match", "wrong row", "page changed")) {
            val events = Events()
            val phone = Notes.screen()
            phone.pages["home"] = Notes.home + tabs
            val current = object : ScreenPort by phone {
                override suspend fun snapshot(): ScreenSnapshot {
                    val screen = phone.snapshot()
                    val moved = scenario == "wrong row" || scenario == "page changed" &&
                        events.all.any { it is RunEvent.Replay && it.matched && it.step == 1 }
                    return if (moved) screen.copy(elements = screen.elements.map {
                        if (it.name == "分类") it.copy(bounds = listOf(400, 320, 550, 380)) else it
                    }) else screen
                }
            }
            val replay = replayer(current, events)
            val outcome = replay.run(RouteChoice(route, slots), fromAppEntry = true)
            if (scenario == "match") {
                assertEquals(ReplayOutcome.Completed(3), outcome)
                assertEquals(listOf(0, 3, 4), replay.matchedSteps)
                val receipts = events.all.filterIsInstance<RunEvent.Replay>()
                assertEquals((1..5).toList(), receipts.map { it.step })
                assertEquals(listOf(2, 3), receipts.filter { it.skipped }.map { it.step })
                assertEquals(listOf("新建"), phone.taps)
                assertEquals(listOf("本次输入"), phone.typed)
            } else {
                assertTrue(outcome.toString(), outcome is ReplayOutcome.Diverged)
                assertTrue(phone.taps.isEmpty())
                assertTrue(phone.typed.isEmpty())
            }
        }
    }

    @Test fun resumesOnlyWhenAllDistinctTerminalLabelsAreVisible() = runTest {
        for (complete in listOf(true, false)) {
            val phone = Notes.screen()
            val labels = setOf("备忘录", if (complete) "全部笔记" else "另一栏")
            val tab = Notes.steps[1]
            phone.links["home" to "新建"] = "home"
            val entry = tab.copy(target = requireNotNull(tab.target).copy(name = "其他入口"))
            val route = Notes.route(listOf(Notes.steps[0], entry, tab)).copy(terminalLabels = labels, packages = listOf("com.notes"))
            val end = replayer(phone).run(RouteChoice(route, emptyList()), fromAppEntry = true)
            if (complete) assertEquals(ReplayOutcome.Completed(2, resumedAtLastTap = true), end)
            else assertTrue(end is ReplayOutcome.Diverged)
            assertEquals(if (complete) listOf("新建") else emptyList(), phone.taps)
        }
    }

    @Test fun restoredSearchEditorExecutesTheCardQueryAndLeavesOtherDraftsAlone() = runTest {
        val labels = Anchors("com.notes", listOf("返回", "男士保温水杯", "拍照购"))
        val open = Notes.steps[1].copy(target = Notes.steps[1].target!!.copy(name = "搜索栏"))
        val type = Notes.steps[2].copy(before = labels)
        val submit = Notes.steps[1].copy(target = Selector("ViewGroup", "搜索 按钮", null, listOf(773, 115, 932, 150), 0, true),
            before = Anchors("com.notes", listOf("返回", "删除", "拍照购")))
        val route = Notes.route(listOf(Notes.steps[0], open, type, submit))
        for (draft in listOf("", "保温杯", "我的其他草稿")) {
            val phone = Notes.screen()
            phone.pages["home"] = listOf(ui("@e1", "text", "返回", listOf(0, 0, 100, 100)),
                ui("@e2", "ViewGroup", "搜索 按钮", submit.target!!.bounds),
                ui("@e6", "TextView", "搜索", listOf(773, 122, 932, 143), clickable = false),
                ui("@e3", "edit", draft, type.target!!.bounds, rid = type.target!!.resourceId, editable = true),
                ui("@e4", "text", "拍照购", listOf(600, 10, 750, 70))) +
                if (draft == "我的其他草稿") listOf(Notes.home[1].copy(ref = "@e5", name = "搜索栏"),
                    Notes.home[0], Notes.home[2]) else emptyList()
            phone.links["home" to "搜索 按钮"] = "editor"
            val success = draft != "我的其他草稿"
            val outcome = replayer(phone).run(RouteChoice(route, listOf("保温杯")), fromAppEntry = true)
            assertEquals("draft=$draft $outcome", success, outcome is ReplayOutcome.Completed)
            assertEquals(if (success) listOf("保温杯") else emptyList<String>(), phone.typed)
            assertEquals(if (success) listOf("搜索 按钮") else emptyList<String>(), phone.taps)
        }
    }

    @Test fun recordsOnlyAnInputActuallyRereadAfterNavigation() = runTest {
        val anchor = Anchors("com.notes", emptyList())
        val submit = Notes.steps[1].copy(target = Selector("button", "搜索", null, listOf(800, 0, 999, 100), 0, true), before = anchor)
        val filter = submit.copy(target = submit.target!!.copy(name = "筛选"))
        val type = Notes.steps[2].copy(before = anchor, after = anchor)
        val route = Notes.route(listOf(Notes.steps[0], Notes.steps[1], type, submit, filter))
        for (shown in listOf("保温杯", "搜索建议")) {
            val phone = Notes.screen()
            val button = ui("@e7", "button", "搜索", submit.target!!.bounds)
            phone.pages["editor"] = Notes.editor + button
            phone.pages["results"] = listOf(button.copy(name = "筛选"), ui("@e8", "text", shown, listOf(0, 120, 200, 160)))
            phone.pages["panel"] = listOf(ui("@e9", "text", "品牌", listOf(0, 120, 200, 160)))
            phone.links["editor" to "搜索"] = "results"
            phone.links["results" to "筛选"] = "panel"
            val dispatcher = toolDispatcher(phone)
            val replay = replayerOn(dispatcher, Events())
            assertTrue(replay.run(RouteChoice(route, listOf("保温杯")), fromAppEntry = true) is ReplayOutcome.Completed)
            assertEquals(if (shown == "保温杯") "保温杯" else null,
                (replay.confirmedInputRead?.get("visibleText") as? kotlinx.serialization.json.JsonPrimitive)?.content)
            val evidence = dispatcher.evidence(fresh = false).ledger.forAnswer("搜索", "已搜索「保温杯」")
            val proofId = replay.confirmedInputRead?.get("observationId")?.toString()?.toInt()
            assertEquals(shown == "保温杯", evidence.any { it.id == proofId && it.where == "com.notes" && it.text == shown })
        }
    }

    @Test fun recordedCoordinatesAreReplacedByTheCurrentVerifiedRef() = runTest {
        val phone = Notes.screen()
        val steps = Notes.steps.take(1) + Notes.steps[1].copy(argsJson = """{"x":50,"y":50,"target":"新建"}""")
        assertEquals(ReplayOutcome.Completed(2), replayer(phone).run(RouteChoice(Notes.route(steps), emptyList()), fromAppEntry = true))
        assertEquals(listOf("新建"), phone.taps)
    }

    @Test fun expiredTapRechecksThePageWithoutRepeatingAnEffectiveTap() = runTest {
        for (alwaysExpired in listOf(false, true)) {
            val phone = Notes.screen()
            var attempts = 0
            val transient = object : ScreenPort by phone {
                override suspend fun tapRef(ref: String, snapshotId: String): ActionResult {
                    attempts++
                    if (alwaysExpired || attempts == 1) return ActionResult.Failed("ref_expired")
                    return phone.tapRef(ref, snapshotId)
                }
            }
            val result = replayer(transient).run(RouteChoice(Notes.route(Notes.steps.take(2)), emptyList()), fromAppEntry = true)
            if (alwaysExpired) {
                assertEquals(3, attempts)
                assertTrue(result is ReplayOutcome.Diverged && !result.executed)
                assertTrue(phone.taps.isEmpty())
            } else {
                assertEquals(ReplayOutcome.Completed(2), result)
                assertEquals(listOf("新建"), phone.taps)
            }
        }
    }

    @Test fun completesWhenAllAnchorsMatch() = runTest {
        val s = Notes.screen(); val sink = Events()
        val out = replayer(s, sink).run(RouteChoice(Notes.route(), listOf("今天天气不错")))
        assertEquals(ReplayOutcome.Completed(3), out)
        assertEquals(listOf("今天天气不错"), s.typed)
        assertEquals(listOf("新建"), s.taps)
        val replays = sink.all.filterIsInstance<RunEvent.Replay>()
        assertEquals(3, replays.size); assertTrue(replays.all { it.matched })
        // 回放的动作也留下路线所需的记录,和工具结果按调用 id 配对
        val targets = sink.all.filterIsInstance<RunEvent.ActionTarget>()
        assertEquals(3, targets.size)
        assertTrue(targets.all { t -> sink.all.filterIsInstance<RunEvent.ToolResult>().any { it.callId == t.callId && it.source == "replay" } })
    }

    @Test fun divergesOnMissingAnchor() = runTest {
        val s = Notes.screen()
        s.pages["home"] = listOf(Notes.home[1], ui("@e4", "text", "登录", listOf(0, 0, 999, 60), clickable = false))
        val out = replayer(s).run(RouteChoice(Notes.route(), listOf("x天气")))
        assertTrue(out is ReplayOutcome.Diverged); assertEquals(1, out.done)
        assertFalse((out as ReplayOutcome.Diverged).executed)
        assertTrue(s.taps.isEmpty()); assertTrue(s.typed.isEmpty())
    }

    @Test fun stopsWhenTargetNotUnique() = runTest {
        // 锚点都在,但同名同 ID 的「新建」有两个(像列表里另一个联系人的同名按钮):不能照点
        val s = Notes.screen()
        s.pages["home"] = Notes.home + ui("@e9", "button", "新建", listOf(820, 880, 999, 980), rid = "n:id/new")
        val out = replayer(s).run(RouteChoice(Notes.route(), listOf("今天天气不错")))
        assertTrue(out is ReplayOutcome.Diverged); assertEquals(1, out.done)
        assertTrue(s.taps.isEmpty())
    }

    @Test fun stopsBeforeIrreversible() = runTest {
        val s = Notes.screen()
        s.pages["home"] = Notes.home + ui("@e5", "button", "提交订单", listOf(0, 900, 300, 999), rid = "n:id/send")
        val steps = Notes.steps.take(1) + RouteStep("phone_tap", "{}", Selector("button", "提交订单", "n:id/send", listOf(0, 900, 300, 999), 0, true), Notes.homeA, Notes.editorA)
        val out = replayer(s).run(RouteChoice(Notes.route(steps), listOf("x")))
        assertEquals(ReplayOutcome.StoppedForConfirmation(1), out)
        assertTrue(s.taps.isEmpty())
    }

    @Test fun typedTextOnlyFromRouteOrGoal() = runTest {
        val s = Notes.screen()
        val steps = Notes.steps + RouteStep("phone_type_text", """{"text":"固定词"}""", Notes.steps[2].target, Anchors("com.notes", listOf("新笔记")), Anchors("com.notes", emptyList()))
        val out = replayer(s).run(RouteChoice(Notes.route(steps), listOf("周末去爬山")))
        assertEquals(ReplayOutcome.Completed(4), out)
        // 端口收到的字只有两种来源:槽值(来自本次原话)与路线里的固定文字
        assertEquals(listOf("周末去爬山", "固定词"), s.typed)
    }

    @Test fun slotNamedTargetResolvedFromNewGoal() = runTest {
        val s = Notes.screen()
        s.pages["home"] = Notes.home + ui("@e6", "button", "购物", listOf(0, 200, 999, 260), rid = "n:id/folder") +
            ui("@e7", "button", "工作", listOf(0, 260, 999, 320), rid = "n:id/folder")
        s.links["home" to "工作"] = "editor"
        val steps = Notes.steps.take(1) + RouteStep("phone_tap", "{}", Selector("button", "{1}", "n:id/folder", listOf(0, 200, 999, 260), 0, true), Notes.homeA, Notes.editorA)
        val route = Route("r2", "打开{1}文件夹", 1, listOf("com.notes"), emptyMap(), steps, lastUsedMs = 0)
        assertEquals(ReplayOutcome.Completed(2), replayer(s).run(RouteChoice(route, listOf("工作"))))
        assertEquals(listOf("工作"), s.taps)
    }

    @Test fun divergesWhenActionHasNoEffect() = runTest {
        val s = Notes.screen()
        s.links.clear() // 点了「新建」没反应,动作后锚点也没出现
        val out = replayer(s).run(RouteChoice(Notes.route(), listOf("x")))
        assertTrue(out is ReplayOutcome.Diverged); assertEquals(1, out.done)
        // 点下去了,只是没看到预期变化:交还时要让模型知道这一步已经执行过
        assertTrue((out as ReplayOutcome.Diverged).executed)
        assertEquals(listOf("新建"), s.taps)
    }

    @Test fun placeholderInAnyStringArgIsFilledOrRefused() = runTest {
        val s = Notes.screen()
        val bad = listOf(RouteStep("phone_launch_app", """{"package":"{2}"}""", null, Anchors(null, emptyList()), Notes.homeA))
        val out = replayer(s).run(RouteChoice(Notes.route(bad), listOf("x")))
        assertTrue(out is ReplayOutcome.Diverged); assertEquals(null, s.pkg)
        val ok = listOf(RouteStep("phone_launch_app", """{"package":"{1}"}""", null, Anchors(null, emptyList()), Notes.homeA))
        assertEquals(ReplayOutcome.Completed(1), replayer(s).run(RouteChoice(Notes.route(ok), listOf("com.notes"))))
        assertEquals("com.notes", s.pkg)
        val nested = listOf(RouteStep("phone_swipe", """{"startX":{"v":"{1}"}}""", null, Anchors(null, emptyList()), Notes.homeA))
        assertTrue(replayer(s).run(RouteChoice(Notes.route(nested), listOf("1"))) is ReplayOutcome.Diverged)
    }

    @Test fun gateBeforeReadAndProtectedPageSeenHidden() = runTest {
        val s = Notes.screen()
        val out = replayer(s, gate = { s.log += "gate" }).run(RouteChoice(Notes.route(), listOf("今天天气不错")))
        assertEquals(ReplayOutcome.Completed(3), out)
        assertEquals("gate", s.log.first())
        // 每步都先过关卡再读屏
        assertEquals(3, s.log.windowed(2).count { it == listOf("gate", "read") })

        val p = Notes.screen().apply { protectedPage = "home" }
        val hidden = replayer(p).run(RouteChoice(Notes.route(), listOf("x"))) as ReplayOutcome.Diverged
        assertEquals("a protected page", hidden.seen)
    }

    @Test fun stableRefsTranslatedToPortRefs() = runTest {
        // 稳定编号开着:回放读到的是模型编号(桌面先占了几个),点击要经分发器翻译回这一屏的端口编号
        val s = Notes.screen().apply {
            pages["launcher"] = listOf(ui("@e1", "text", "桌面", listOf(0, 0, 999, 60), clickable = false), ui("@e2", "button", "相机", listOf(0, 100, 200, 200)))
        }
        val sink = Events()
        val d = toolDispatcher(s, stableRefs = true).also { it.captureTargets = true }
        d.execute(com.wangzan101.mika.core.model.Part.ToolCall("o", "phone_observe", "{}"))
        val out = replayerOn(d, sink)
            .run(RouteChoice(Notes.route(), listOf("今天天气不错")))
        assertEquals(ReplayOutcome.Completed(3), out)
        assertEquals(listOf("新建"), s.taps)
        assertEquals(listOf("今天天气不错"), s.typed)
        // 运行记录里两套编号都有:模型编号不是端口编号
        val tap = sink.all.filterIsInstance<RunEvent.ToolResult>().single { it.tool == "phone_tap" && it.source == "replay" }
        assertTrue(tap.resultJson, "\"portRef\":\"@e2\"" in tap.resultJson)
    }

    private fun linkRoute() = Notes.route(listOf(
        RouteStep("phone_open_link", """{"uri":"notes://new","package":"com.notes"}""", null, Anchors(null, emptyList()), Notes.editorA),
    ))

    private class LinkScreen(val result: ActionResult) : ScreenPort by Notes.screen() {
        var opened = 0
        override suspend fun openLink(action: String?, uri: String?, packageName: String?): ActionResult { opened++; return result }
    }

    // 路线里记着的链接此刻不在参考资料里:不去开,交还时说明没执行
    @Test fun linkNoLongerAllowedStopsBeforeOpening() = runTest {
        val s = LinkScreen(ActionResult.Ok(changed = true))
        val d = toolDispatcher(s, deepLinks = true).also { it.captureTargets = true }
        val out = replayerOn(d, Events()).run(RouteChoice(linkRoute(), emptyList()))
        assertTrue(out is ReplayOutcome.Diverged)
        assertFalse((out as ReplayOutcome.Diverged).executed)
        assertEquals(0, s.opened)
    }

    // 执行前就被拒(另一个任务占着 App):executed 为假,错误码交给模型
    @Test fun preExecutionRejectionIsNotExecuted() = runTest {
        val s = LinkScreen(ActionResult.Failed("app_in_use_by_other_task"))
        val d = toolDispatcher(s, deepLinks = true).also { it.captureTargets = true; it.allowLinks(listOf(Link("新建笔记", "notes://new", "com.notes"))) }
        val out = replayerOn(d, Events()).run(RouteChoice(linkRoute(), emptyList()))
        out as ReplayOutcome.Diverged
        assertFalse(out.executed)
        assertEquals("app_in_use_by_other_task", out.error)
        assertEquals(1, s.opened)
    }
}
