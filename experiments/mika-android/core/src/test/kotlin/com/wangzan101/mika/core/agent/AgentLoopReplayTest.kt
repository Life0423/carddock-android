package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.replay.Events
import com.wangzan101.mika.core.replay.MemRoutes
import com.wangzan101.mika.core.replay.Notes
import com.wangzan101.mika.core.replay.PagedScreen
import com.wangzan101.mika.core.replay.RouteMatcher
import com.wangzan101.mika.core.replay.RouteChoice
import com.wangzan101.mika.core.replay.RouteStep
import com.wangzan101.mika.core.replay.ui
import com.wangzan101.mika.core.verify.Verifier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.assembly.toolDispatcher

class AgentLoopReplayTest {
    private var seq = 0
    private fun call(name: String, args: String = "{}") = ModelReply(listOf(Part.ToolCall("m${++seq}", name, args)), StopReason.ToolUse, null)
    private fun text(s: String) = ModelReply(listOf(Part.Text(s)), StopReason.EndTurn, null)
    private val finish = text("写好了,屏上有新笔记")
    private val pick = """{"route": 1, "slots": ["今天天气不错"]}"""
    private val pass = """{"checks": [{"criterion": "t", "status": "met", "quote": "新笔记"}], "verdict": "pass", "reason": "屏上有"}"""
    private val goal = "在备忘录里写今天天气不错"

    private class Run(val sink: Events, val main: FakeModel, val store: MemRoutes, val screen: PagedScreen)

    private fun run(
        screen: PagedScreen = Notes.screen(), store: MemRoutes = MemRoutes(Notes.route()), main: FakeModel = FakeModel(finish),
        matcher: ChatModel = FakeModel(text(pick)), flags: SpeedFlags = SpeedFlags(replay = true), verifier: Verifier? = null,
        task: String = goal, pinned: RouteChoice? = null, visionEnabled: Boolean = false,
        /** 交出回答后判的结局(产品里在结果交给用户之后才判);路线只在办成时记成功。 */
        outcome: RunOutcome = RunOutcome.Done,
    ): Run {
        val sink = Events()
        runBlocking {
            val agent = AgentLoop(main, toolDispatcher(screen, visionEnabled = visionEnabled), "BASE", sink = sink, flags = flags, verifier = verifier,
                routes = RouteMatcher(matcher, store, null, pinned))
            agent.run(task)
            agent.noteOutcome(outcome)
        }
        return Run(sink, main, store, screen)
    }

    @Test fun unidentifiedTapRefreshesWithoutExecutingItAndRecordsTheStep() = runBlocking {
        for (misses in 1..3) {
            val repeatMissing = misses == 3
            val screen = Notes.screen().apply { pkg = "com.notes"; page = "home" }
            val sink = Events()
            var turn = 0
            val model = object : ChatModel by FakeModel() {
                override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                    turn++
                    if (turn == 1) return call("phone_tap", """{"ref":"@e99","snapshotId":"old"}""")
                    if (turn > misses + 1) return text("已打开目标页面")
                    val results = messages.flatMap { it.parts }.filterIsInstance<Part.ToolResult>()
                    assertTrue(results.any { "target_unresolved" in it.content })
                    val read = results.last { it.callId.startsWith("target_recovery_") }
                    val snapshot = kotlinx.serialization.json.Json.parseToJsonElement(read.content) as kotlinx.serialization.json.JsonObject
                    if (turn == 2) assertTrue(messages.flatMap { it.parts }.any { it is Part.Image })
                    val ref = if (turn <= misses) "@e99" else "@e2"
                    return call("phone_tap", """{"ref":"$ref","snapshotId":${snapshot["snapshotId"]}}""")
                }
            }
            val agent = AgentLoop(model, toolDispatcher(screen, visionEnabled = true), "BASE", sink = sink, reviewFinalAnswer = false,
                confirmations = com.wangzan101.mika.core.gate.ConfirmationGate(),
                approvals = object : com.wangzan101.mika.core.action.ApprovalSettings {
                    override fun autonomous() = false
                    override val browseRoute = true
                })
            val end = agent.run("打开有文字标签的入口")
            assertEquals("$end ${sink.all.filterIsInstance<RunEvent.ToolResult>()}",
                if (repeatMissing) emptyList<String>() else listOf("新建"), screen.taps)
            val refused = sink.all.filterIsInstance<RunEvent.ToolResult>().filter { "target_unresolved" in it.resultJson }
            assertEquals((1..misses).toList(), refused.map { it.step })
            assertEquals(1, sink.all.filterIsInstance<RunEvent.ToolResult>().count { it.callId == "target_recovery_1" })
            if (repeatMissing) assertTrue((end as TaskState.Failed).reason.contains("第 3 步"))
            else assertTrue(end is TaskState.Succeeded)
        }
    }

    @Test fun trialRecordsLaunchFromAnEmptyDisplayAndPinnedCardRefusesExtraActions() = runBlocking {
        val screen = Notes.screen()
        val sink = Events()
        val target = "打开备忘录并读出首页"
        val trial = AgentLoop(FakeModel(call("phone_launch_app", """{"package":"com.notes"}"""), text("首页显示备忘录")),
            toolDispatcher(screen), "BASE", sink = sink, flags = SpeedFlags(replay = true), reviewFinalAnswer = false)
        trial.run(target)
        val route = requireNotNull(com.wangzan101.mika.core.replay.RouteRecorder.record(target, sink.all, 1,
            toolDispatcher(screen).index))
        assertEquals(listOf("phone_launch_app"), route.steps.map { it.tool })
        val choice = com.wangzan101.mika.core.replay.RouteChoice(route, emptyList())
        val extra = call("phone_launch_app", """{"package":"com.other"}""")
        for (acceptRead in listOf(false, true)) {
            val card = AgentLoop(FakeModel(extra, if (acceptRead) text("首页显示备忘录") else extra), toolDispatcher(screen),
                "BASE", flags = SpeedFlags(replay = true), reviewFinalAnswer = false,
                routes = RouteMatcher(FakeModel(), MemRoutes(), null, choice))
            val end = card.run(target)
            if (acceptRead) assertTrue(end is TaskState.Succeeded) else assertTrue(end is TaskState.Failed)
            assertEquals("com.notes", screen.pkg)
        }
    }

    @Test fun completedRouteMayReconsiderFailureOnceAndStillReportAMissingField() = runBlocking {
        val task = "按本次确认的口径读取可见标题"
        val choice = RouteChoice(Notes.route(Notes.steps.take(1)).copy(template = task, slots = 0), emptyList())
        val stop = call("end_not_done", """{"message":"当前页未显示日期","subtype":"interaction_failed"}""")
        for (canAnswer in listOf(true, false)) {
            val main = FakeModel(stop, if (canAnswer) text("首页标题是「备忘录」。") else stop)
            val sink = Events()
            val agent = AgentLoop(main, toolDispatcher(Notes.screen()), "BASE", sink = sink,
                flags = SpeedFlags(replay = true), routes = RouteMatcher(FakeModel(), MemRoutes(), null, choice))
            val end = agent.run(task)
            if (canAnswer) assertTrue(end is TaskState.Succeeded && end.verified) else assertTrue(end is TaskState.Failed)
            assertEquals(1, sink.all.filterIsInstance<RunEvent.State>().count { it.name == "scope_recheck" })
            assertEquals(1, sink.all.filterIsInstance<RunEvent.Replay>().count { it.matched })
        }
    }

    @Test fun pinnedCardChecksCorrectedAnswerAgainstTheSameRead() = runBlocking {
        val task = "读出当前应用首页标题"
        val route = Notes.route(Notes.steps.take(1)).copy(template = task, slots = 0)
        val choice = com.wangzan101.mika.core.replay.RouteChoice(route, emptyList())
        val draft = "首页标题是「备忘录」，还有按钮 \"More\" 和 \"Add city\"。"
        // 同一份读屏分别验证修正成功、仍坚持无依据答复两种结局。
        for (corrected in listOf("首页标题是「备忘录」。", draft)) {
            val main = FakeModel(text(draft), text(corrected))
            val sink = Events()
            val agent = AgentLoop(main, toolDispatcher(Notes.screen()), "BASE", sink = sink, flags = SpeedFlags(replay = true),
                routes = RouteMatcher(FakeModel(), MemRoutes(), null, choice))
            val end = agent.run(task)
            val checks = sink.all.filterIsInstance<RunEvent.AnswerCheck>()
            val missing = listOf("More", "Add city")
            assertEquals(missing, checks.first().unmatched)
            assertEquals(if (corrected == draft) missing else emptyList<String>(), checks.last().unmatched)
            assertTrue(checks.last().claims > 0)
            assertEquals(corrected != draft, (end as TaskState.Succeeded).verified)
            assertEquals(corrected != draft, LearnGate.done(end, RunOutcome.Done))
            assertTrue(sink.all.filterIsInstance<RunEvent.Replay>().single().matched)
            val retry = main.requests.last().flatMap { it.parts }.filterIsInstance<Part.Text>()
            assertTrue(retry.any { "Remove unrequested details" in it.text })
        }
    }

    @Test fun pinnedCardUsesVisibleScopeWhenTheDraftRepeatsGoalWording() = runBlocking {
        val task = "查询今天的记录，显示首页实际标题"
        val choice = com.wangzan101.mika.core.replay.RouteChoice(
            Notes.route(Notes.steps.take(1)).copy(template = task, slots = 0), emptyList())
        val main = FakeModel(text("首页标题是「备忘录」，你说的「今天」无法核实。"), text("首页标题是「备忘录」。"))
        val sink = Events()
        val agent = AgentLoop(main, toolDispatcher(Notes.screen()), "BASE", sink = sink, flags = SpeedFlags(replay = true),
            routes = RouteMatcher(FakeModel(), MemRoutes(), null, choice))
        val end = agent.run(task) as TaskState.Succeeded
        assertEquals(listOf(listOf("今天"), emptyList<String>()), sink.all.filterIsInstance<RunEvent.AnswerCheck>().map { it.unmatched })
        assertTrue(main.requests.last().flatMap { it.parts }.filterIsInstance<Part.Text>().any { "实际适用范围" in it.text })
        assertTrue(end.verified)
    }

    @Test fun pinnedBrowseSummaryCanDropAnUnobservedComparisonWithoutWaivingEvidence() = runBlocking {
        val goal = "查询今天的记录，显示首页实际标题"
        val choice = RouteChoice(Notes.route(Notes.steps.take(1)).copy(template = goal, slots = 0), emptyList())
        val mixed = "首页标题是「备忘录」，无法核实自然日“今天”。"
        for (last in listOf("首页标题是「备忘录」。", mixed)) {
            val sink = Events()
            val agent = AgentLoop(FakeModel(text(mixed), text(mixed), text(last)), toolDispatcher(Notes.screen()), "BASE", sink = sink,
                flags = SpeedFlags(replay = true), routes = RouteMatcher(FakeModel(), MemRoutes(), null, choice),
                approvals = object : com.wangzan101.mika.core.action.ApprovalSettings {
                    override fun autonomous() = false
                    override val browseRoute = true
                })
            val end = agent.run(goal) as TaskState.Succeeded
            assertEquals(3, sink.all.filterIsInstance<RunEvent.AnswerCheck>().size)
            assertEquals(last != mixed, end.verified)
            assertEquals(if (last == mixed) listOf("今天") else emptyList<String>(), end.unconfirmed)
        }
    }

    @Test fun quotedNamesFromTheCardMustAlsoAppearOnTheScreen() = runBlocking {
        for ((label, visible) in mapOf("备忘录" to true, "草稿箱" to false, "开" to true, "录" to false)) {
            val task = "请核实首页是否有标签「$label」"
            val screen = Notes.screen()
            screen.pages["home"] = Notes.home + ui("@e4", "text", "开", listOf(0, 120, 999, 180), clickable = false)
            val answer = text("首页有「$label」。")
            val choice = com.wangzan101.mika.core.replay.RouteChoice(
                Notes.route(Notes.steps.take(1)).copy(template = task, slots = 0), emptyList())
            val agent = AgentLoop(FakeModel(answer, answer), toolDispatcher(screen), "BASE",
                flags = SpeedFlags(replay = true), routes = RouteMatcher(FakeModel(), MemRoutes(), null, choice))
            val end = agent.run(task) as TaskState.Succeeded
            assertEquals(visible, end.verified)
            assertEquals(if (visible) emptyList<String>() else listOf(label), end.unconfirmed)
        }
    }

    @Test fun pinnedCardNeedsEvidenceEvenWhenTheAnswerHasNoLiteralClaims() = runBlocking {
        val target = "读出当前应用首页标题"
        val choice = com.wangzan101.mika.core.replay.RouteChoice(
            Notes.route(Notes.steps.take(1)).copy(template = target, slots = 0), emptyList())
        val noQuote = text("已读取首页标题。")
        for (second in listOf(noQuote, text("首页标题是「备忘录」。"))) {
            val sink = Events()
            val agent = AgentLoop(FakeModel(noQuote, second), toolDispatcher(Notes.screen()), "BASE", sink = sink,
                flags = SpeedFlags(replay = true), routes = RouteMatcher(FakeModel(), MemRoutes(), null, choice))
            val end = agent.run(target) as TaskState.Succeeded
            assertEquals(0, sink.all.filterIsInstance<RunEvent.AnswerCheck>().first().claims)
            assertEquals(second != noQuote, end.verified)
            assertEquals(second != noQuote, LearnGate.done(end, RunOutcome.Done))
        }
    }

    /** 交还时补进对话的全部文字:说明、以及读屏那对调用的结果。 */
    private fun firstPrompt(r: Run) = r.main.requests.first().drop(1).flatMap { it.parts }.joinToString("") {
        when (it) {
            is Part.Text -> it.text
            is Part.ToolResult -> it.content
            else -> ""
        }
    }

    @Test fun resumedHandbackListsTheExecutedTapAndInputUsingTheirRecordedNumbers() {
        val back = RouteStep("phone_back", "{}", null, Notes.editorA, Notes.homeA)
        val route = Notes.route(Notes.steps.take(1) + back + back + Notes.steps.drop(1))
        val run = run(store = MemRoutes(route), pinned = RouteChoice(route, listOf("今天天气不错")), visionEnabled = true)
        val prompt = firstPrompt(run)
        assertTrue(prompt, "4. phone_tap" in prompt && "5. phone_type_text" in prompt)
        assertFalse(prompt, "2. phone_back" in prompt || "3. phone_back" in prompt)
        val handback = run.main.requests.first().dropWhile { m -> m.parts.none { it is Part.ToolCall && it.id == "replay_handback" } }
        assertTrue(handback.isNotEmpty() && handback.flatMap { it.parts }.none { it is Part.Image })
        assertEquals(listOf("今天天气不错"), run.screen.typed)
    }

    @Test fun pinnedDivergenceDoesNotCaptureTheUnexpectedAppForHandback() = runBlocking {
        val events = Events()
        val phone = Notes.screen()
        var readsAfterStop = 0
        val switched = object : com.wangzan101.mika.core.uistate.ScreenPort by phone {
            override suspend fun tapRef(ref: String, snapshotId: String) =
                com.wangzan101.mika.core.uistate.ActionResult.Ok(changed = true, appSwitched = "com.calculator", switchedByTarget = true)
            override suspend fun snapshot(): com.wangzan101.mika.core.uistate.ScreenSnapshot {
                if (events.all.any { it is RunEvent.Replay && !it.matched }) readsAfterStop++
                return phone.snapshot()
            }
        }
        val route = Notes.route(Notes.steps.take(2))
        val main = FakeModel(finish)
        val loop = AgentLoop(main, toolDispatcher(switched), "BASE", sink = events,
            flags = SpeedFlags(replay = true), routes = RouteMatcher(FakeModel(), MemRoutes(route), null, RouteChoice(route, emptyList())))
        val end = loop.run(goal)
        assertTrue(end.toString(), end is TaskState.Failed && "com.calculator" in end.reason)
        assertEquals(0, readsAfterStop)
        assertTrue(main.requests.isEmpty())
    }

    @Test fun goalsThatNameTheWebAreNotReplayed() {
        val matcher = FakeModel(text(pick))
        val r = run(matcher = matcher, task = "用网页$goal")
        assertTrue(r.sink.all.filterIsInstance<RunEvent.Replay>().isEmpty())
        assertTrue(matcher.requests.isEmpty())
    }

    @Test fun divergedHandsBackWithSummaryAndFullScreen() {
        val s = Notes.screen()
        s.pages["home"] = listOf(ui("@e4", "text", "请先登录", listOf(0, 0, 999, 60), clickable = false))
        val r = run(screen = s)
        val msg = firstPrompt(r)
        assertTrue(msg, msg.contains("1 of 3 steps were run automatically, following a way that worked before"))
        assertTrue(msg, msg.contains("1. phone_launch_app com.notes"))
        assertTrue(msg, msg.contains("Step 2 expected"))
        assertTrue(msg, msg.contains("请先登录"))
        // 带着一份全量读屏(编号与 snapshotId),模型可直接接着做
        assertTrue(msg, msg.contains("\"snapshotId\"") && msg.contains("\"ref\":\"@e4\""))
        // 读屏是一对调用与结果(助手的调用在前,结果紧跟),各家接口的配对要求都满足
        val h = r.main.requests.first().withoutRuntime()
        val callAt = h.indexOfFirst { m -> m.role == Role.Assistant && m.parts.any { it is Part.ToolCall && it.id == "replay_handback" && it.name == "phone_list_elements" } }
        assertTrue(callAt > 0)
        assertEquals("replay_handback", (h[callAt + 1].parts.single() as Part.ToolResult).callId)
        assertEquals(callAt + 2, h.size)
        assertEquals(listOf(true, false), r.sink.all.filterIsInstance<RunEvent.Replay>().map { it.matched })
        assertEquals(1, r.store.m["r1"]!!.consecutiveMisses)
        assertFalse(r.store.m["r1"]!!.disabled)
    }

    @Test fun executedButUnchangedStepToldToModel() {
        val s = Notes.screen().apply { links.clear() }
        val msg = firstPrompt(run(screen = s))
        assertTrue(msg, msg.contains("Step 2 was run, but the expected change did not show"))
    }

    @Test fun unreadableHandbackScreenStillContinues() {
        val s = Notes.screen().apply { failReads = true }
        val r = run(screen = s)
        val msg = firstPrompt(r)
        assertTrue(msg, msg.contains("The current screen could not be read"))
        assertEquals("succeeded", r.sink.all.filterIsInstance<RunEvent.TaskEnded>().single().outcome)
    }

    @Test fun completedStillRequiresAnAnswer() {
        // 走完了也要模型自己回答;回答核实过才算路线的一次成功:这个回答引用的「新笔记」读到过(字面核对对上了,不用另请核查)
        val ok = run(main = FakeModel(text("写好了,在「新笔记」里")), verifier = Verifier(FakeModel(text(pass)), null))
        assertTrue(firstPrompt(ok).contains("All steps of the earlier way of doing this were run"))
        assertEquals(1, ok.main.requests.size)
        assertTrue(ok.sink.all.none { it is RunEvent.Verifier })
        assertEquals(1, ok.store.m["r1"]!!.successes)

        val gaveUp = run(main = FakeModel(call("end_not_done", """{"message":"没写上"}""")), verifier = Verifier(FakeModel(text(pass)), null))
        assertTrue(gaveUp.sink.all.none { it is RunEvent.Verifier })
        assertEquals(0, gaveUp.store.m["r1"]!!.successes)

        // 回答里没有可核对的值、也没请核查:判下来办成就记成功(学习看结局,不看核查)
        assertEquals(1, run().store.m["r1"]!!.successes)
        // 核对过却没对上(退回一次后仍标成未确认):不记成功
        val doubtful = run(main = FakeModel(text("写好了,在「旧草稿 42 号」里"), text("写好了,在「旧草稿 42 号」里")))
        assertEquals(0, doubtful.store.m["r1"]!!.successes)
    }

    @Test fun routeSuccessOnlyWhenJudgedDone() {
        // 回答核实过,但判下来只办了一部分、没办成或没判成:都不记成功
        for (o in listOf(RunOutcome.Partial, RunOutcome.NotPossible, RunOutcome.Unknown)) {
            val r = run(main = FakeModel(text("写好了,在「新笔记」里")), verifier = Verifier(FakeModel(text(pass)), null), outcome = o)
            assertEquals(o.wire, 0, r.store.m["r1"]!!.successes)
        }
    }

    @Test fun twoMissesAtSameStepDisableRoute() {
        val store = MemRoutes(Notes.route())
        fun broken() = Notes.screen().apply { pages["home"] = listOf(ui("@e4", "text", "请先登录", listOf(0, 0, 999, 60), clickable = false)) }
        run(screen = broken(), store = store)
        assertFalse(store.m["r1"]!!.disabled)
        run(screen = broken(), store = store)
        assertTrue(store.m["r1"]!!.disabled)
        // 停用之后不再是候选:匹配模型不会被调用
        val m = FakeModel()
        run(store = store, matcher = m)
        assertTrue(m.requests.isEmpty())
    }

    @Test fun replayOffNoMatcherCall() {
        val m = FakeModel()
        val r = run(matcher = m, flags = SpeedFlags.OFF)
        assertTrue(m.requests.isEmpty())
        assertTrue(r.sink.all.none { it is RunEvent.Replay })
        assertTrue(r.screen.typed.isEmpty())
    }

    @Test fun noModelCallsDuringReplay() {
        val s = Notes.screen()
        var typedWhenFirstAsked: List<String>? = null
        // 主模型一被调用就记下当时端口收到的输入:回放全程不碰主模型
        val main = object : ChatModel by FakeModel() {
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                if (typedWhenFirstAsked == null) typedWhenFirstAsked = s.typed.toList()
                return finish
            }
        }
        val sink = Events()
        val matcher = FakeModel(text(pick))
        runBlocking {
            AgentLoop(main, toolDispatcher(s), "BASE", sink = sink, flags = SpeedFlags(replay = true),
                routes = RouteMatcher(matcher, MemRoutes(Notes.route()), null)).run(goal)
        }
        assertEquals(listOf("今天天气不错"), typedWhenFirstAsked)
        assertEquals(1, matcher.requests.size)
        val firstReply = sink.all.indexOfFirst { it is RunEvent.ModelReply }
        assertTrue(sink.all.withIndex().filter { it.value is RunEvent.Replay }.all { it.index < firstReply })
    }
}
