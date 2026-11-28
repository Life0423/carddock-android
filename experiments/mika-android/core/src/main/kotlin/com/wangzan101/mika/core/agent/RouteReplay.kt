package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.observation.Place
import com.wangzan101.mika.core.observation.tag
import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.replay.ReplayOutcome
import com.wangzan101.mika.core.uistate.interfaces
import com.wangzan101.mika.core.replay.Replayer
import com.wangzan101.mika.core.replay.Route
import com.wangzan101.mika.core.replay.RouteMatcher
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.Verb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val REPLAY_SOURCE: String get() = Feedback["replay_source"]
private val REPLAY_SCREEN_HEADER: String get() = Feedback["replay_screen_header"]
private val REPLAY_NO_SCREEN: String get() = Feedback["replay_no_screen"]

/**
 * 经验回放:第一次模型调用之前,有合适的路线就先照着做,做完或对不上都交还模型;任务结束后按结局更新路线的计数。
 * 路线出了问题(匹配、读写路线)都不影响任务。每个任务开始时 [reset]。
 */
internal class RouteReplay(
    private val routes: RouteMatcher,
    private val tools: ToolDispatcher,
    private val gate: suspend () -> Unit,
    /** 对外显示回放进度(也是关卡等完之后恢复的状态)。 */
    private val show: (TaskState.Running) -> Unit,
    private val emit: (RunEvent) -> Unit,
    private val clock: () -> Long,
) {
    /** 本次任务回放过的路线与结果。 */
    private var replayed: Pair<Route, ReplayOutcome>? = null

    val completed get() = replayed?.second is ReplayOutcome.Completed

    /** 展示实际断点，完整页面及工具诊断保留在同一回执里。 */
    val failureDetail: String get() = when (val out = replayed?.second) {
        is ReplayOutcome.Diverged -> if (out.error == "app_in_use_by_user")
            "第 ${out.done + 1} 步：目标应用正在主屏使用，请先退出该应用后重试。"
            else "第 ${out.done + 1} 步未匹配。预期：${out.expected.take(220)}。当前页面：${out.seen.take(140).ifBlank { "无法读取" }}。"
        is ReplayOutcome.StoppedForConfirmation -> "第 ${out.done + 1} 步需要操作授权，浏览路线已停止。"
        else -> "这次没有从已保存的路线读到目标页面。请检查应用或重新试查。"
    }
    fun reset() { replayed = null }

    /** 交还时读屏用最近操作的那块屏的读元素工具(回放的路线落在哪块屏就读哪块)。 */
    private val dumpTool: String get() = tools.index.nameOf(Verb.ListElements, tools.surface)!!

    /**
     * 找路线并回放;回放过就把经过(做了哪几步、在哪对不上)和一份当前全量读屏交给模型,返回读屏里的前台 App。
     */
    suspend fun run(task: String, history: MutableList<Message>, executor: ActionExecutor, ctx: ContextBuilder, track: StepTrack, officialEndpoint: (com.wangzan101.mika.core.uistate.ScreenSnapshot) -> Boolean = { false }): Place? {
        val choice = try {
            withContext(Dispatchers.IO) { routes.choose(task) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        val total = choice.route.steps.size
        var running = TaskState.Running(0, REPLAY_NOTE, progress = RunProgress.Replay(1, total))
        show(running)
        val replayer = Replayer(
            tools, executor, gate = gate,
            sink = object : RunSink {
                override fun event(e: RunEvent) {
                    emit(e)
                    // 一步对上了就显示下一步
                    if (e is RunEvent.Replay && e.matched && e.step < e.total) {
                        running = running.copy(progress = RunProgress.Replay(e.step + 1, e.total))
                        show(running)
                    }
                }
                override fun media(mediaId: Int, png: ByteArray) = Unit
            },
            clock = clock,
        )
        val out = replayer.run(choice, fromAppEntry = routes.pinned != null, officialEndpoint = officialEndpoint)
        replayed = choice.route to out
        update(choice.route.id) { r ->
            when (out) {
                is ReplayOutcome.Diverged -> {
                    val misses = if (r.missStep == out.done) r.consecutiveMisses + 1 else 1
                    r.copy(consecutiveMisses = misses, missStep = out.done, disabled = r.disabled || misses >= ROUTE_MISSES_TO_DISABLE, lastUsedMs = clock())
                }
                else -> r.copy(lastUsedMs = clock())
            }
        }
        // 固定卡片没有模型接手续办；保留原断点，避免停下后又采集意外切入的应用。
        if (routes.pinned != null && out !is ReplayOutcome.Completed) return null
        val steps = choice.route.steps
        val done = replayer.matchedSteps
        val doneLines = done.joinToString("\n") { i -> "${i + 1}. ${Replayer.describe(steps[i], choice.slots)}" }
        val head = when (out) {
            is ReplayOutcome.Completed -> if (out.resumedAtLastTap) "已在应用中定位到保存的页签，重新点击末步入口后已核对本次页面标识。请读取当前结果。"
                else if (done.size < total) "已从现场核实的页面接续，实际执行了以下 ${done.size} 步。请读取当前结果。\n$doneLines"
                else Feedback.format("replay.completed", "total" to steps.size, "steps" to doneLines)
            is ReplayOutcome.Diverged -> {
                val error = out.error?.let { Feedback.format("replay.error_code", "code" to it) }.orEmpty()
                val next = if (out.executed) Feedback.format("replay.diverged_executed", "step" to (out.done + 1), "expected" to out.expected, "seen" to out.seen, "error" to error)
                else Feedback.format("replay.diverged_skipped", "step" to (out.done + 1), "expected" to out.expected, "seen" to out.seen, "error" to error)
                Feedback.format("replay.progress", "done" to done.size, "total" to steps.size, "steps" to doneLines.ifEmpty { Feedback["replay.no_steps"] }, "next" to next)
            }
            is ReplayOutcome.StoppedForConfirmation -> Feedback.format("replay.progress", "done" to done.size, "total" to steps.size, "steps" to doneLines.ifEmpty { Feedback["replay.no_steps"] },
                "next" to Feedback.format("replay.stopped", "step" to (out.done + 1), "action" to Replayer.describe(steps[out.done], choice.slots)))
        }
        // 当前全量读屏:模型从这里拿编号接着做;读不到就只交说明,任务照常继续
        val resumed = out is ReplayOutcome.Completed && out.resumedAtLastTap
        val checkedEnd = out is ReplayOutcome.Completed && (resumed || routes.pinned != null && choice.route.packages.size == 1)
        val looked = if (checkedEnd) replayer.lastRead else try {
            tools.executeRead(Part.ToolCall("replay_handback", dumpTool, "{}"), 0).takeIf { it.content["ok"] == JsonPrimitive(true) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val read = looked?.content
        if (read != null) {
            // 读屏做成一对工具调用与结果:裁剪和「模型看得到哪几屏」按读屏工具认它,各家接口对调用和结果的配对要求也都满足
            val id = "replay_handback"
            val groups = if (checkedEnd) com.wangzan101.mika.core.verify.ListQuantityCheck.groups(tools.interfaces.session.lastSnapshot)
                .filterKeys { it in choice.confirmedScope } else emptyMap()
            val note = buildString {
                append(REPLAY_SCREEN_HEADER)
                if (groups.isNotEmpty()) append("\n下附本次可成行读取的清单计数。仅统计了本屏逐行可核实的条目；回答相关数量时引用相应分组，不推算其他页。")
                if (checkedEnd && replayer.confirmedPageLabels.isNotEmpty())
                    append("\n另附本次进入子页前现场核实的页标（含来源编号）。据此核对当前列表属于哪个对象，列表内容和状态只取本次终点读屏。")
                if (checkedEnd && replayer.confirmedInputRead != null)
                    append("\n另附本次较早页面回读的输入条件。可据此核对搜索词等固定输入；品牌、价格等结果取当前页面。")
                if (checkedEnd && replayer.checkedEntryReads.any { it["source"] == JsonPrimitive("elements") })
                    append("\n请在答复中说明截图未核实图形、实际从已核实的文字入口打开，再报告当前面板读到的结果。")
            }
            history += Message(Role.Assistant, listOf(Part.Text(Feedback.format("replay.handback", "head" to head, "note" to note)), Part.ToolCall(id, dumpTool, "{}")), Author.Mika)
            val inputRead = replayer.confirmedInputRead?.takeIf { checkedEnd }
            val evidence = JsonObject(read.toMutableMap().apply {
                if (groups.isNotEmpty()) put("visibleRowCounts", JsonObject(groups.mapValues { JsonPrimitive(it.value.size) }))
                if (checkedEnd && replayer.confirmedPageLabels.isNotEmpty())
                    put("earlierVerifiedPageLabelsOnThisRun", JsonArray(replayer.confirmedPageLabels.values.toList()))
                if (inputRead != null) put("earlierConfirmedInputOnThisRun", inputRead)
                if (checkedEnd && replayer.checkedEntryReads.isNotEmpty())
                    put("earlierCheckedEntryActionsOnThisRun", JsonArray(replayer.checkedEntryReads))
            })
            history += Message(Role.Tool, listOf(Part.ToolResult(id, evidence.toString(), isError = false, looked?.observations.orEmpty().map { it.tag })), Author.Mika)
            // 固定路线的终点来自已核对归属的控件树。另截的图可能属于随后切入的窗口。
            if (!checkedEnd) tools.imageNow()?.let { (png, shotId) -> history += Message(Role.User, listOf(Part.Text(Feedback["action_frame_note"]), tools.modelImage(png, shotId)), Author.Mika) }
        } else {
            history += ctx.notice(REPLAY_SOURCE, Feedback.format("replay.handback", "head" to head, "note" to REPLAY_NO_SCREEN))
        }
        // 交还之后的一步按恢复步算(大小模型分工时交给主模型)
        track.markRecovery()
        return looked?.place
    }

    /** 回放走完且这次运行算办成了([LearnGate],[outcome] 是交出回答后判的结局):成功计数 +1、清掉连续失误。 */
    suspend fun noteResult(end: TaskState, outcome: RunOutcome) {
        val (route, out) = replayed ?: return
        if (out !is ReplayOutcome.Completed || !LearnGate.done(end, outcome)) return
        update(route.id) { it.copy(successes = it.successes + 1, consecutiveMisses = 0, missStep = null) }
    }

    private suspend fun update(id: String, change: (Route) -> Route) {
        val store = routes.store
        try {
            // 读改写一次完成:并行的另一个任务同时改这条路线时计数不会丢
            withContext(Dispatchers.IO) { store.update(id, change) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 路线计数写不进去不影响任务
        }
    }

    companion object {
        /** 回放期间界面上这一步的说明。 */
        const val REPLAY_NOTE = "按以往的做法操作"
        /** 同一步连续对不上这么多次就停用路线。 */
        const val ROUTE_MISSES_TO_DISABLE = 2
    }
}
