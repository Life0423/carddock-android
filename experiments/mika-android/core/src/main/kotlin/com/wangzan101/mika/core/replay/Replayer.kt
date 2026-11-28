package com.wangzan101.mika.core.replay

import com.wangzan101.mika.core.agent.LearnGate
import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionOutcome
import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.record.Redactor
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.uistate.interfaces
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.ToolExecution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.hypot
import com.wangzan101.mika.core.tool.Verb

sealed interface ReplayOutcome {
    /** 走完(或核对通过)的步数。 */
    val done: Int

    data class Completed(override val done: Int, val resumedAtLastTap: Boolean = false) : ReplayOutcome
    /**
     * [executed]:第 done+1 步真的执行了(界面可能已经动过);执行前就被拒的(参数、链接、编号过期、App 被占等)为 false。
     * [error] 是那一步工具结果的错误码,交还时一并告诉模型。
     */
    data class Diverged(
        override val done: Int, val expected: String, val seen: String, val executed: Boolean = false, val error: String? = null,
    ) : ReplayOutcome
    /** 下一步是不可逆操作(或判不清),停在它之前交还模型走正常确认流程。 */
    data class StoppedForConfirmation(override val done: Int) : ReplayOutcome
}

/**
 * 不调模型地照路线一步步做。每步:锁屏关卡 → 读屏 → 轻量核对(前台包名、动作前锚点、选择器唯一命中)→
 * 经 [ActionExecutor] 执行(守卫一有提示就停;不可逆或判不清都停,回放从不认为用户已同意)→ 核对动作后的变化。
 * 任何一处对不上就停,交还模型。输入的字只来自路线里的固定文字和本次原话里的槽值。
 */
class Replayer(
    private val tools: ToolDispatcher,
    private val executor: ActionExecutor,
    private val gate: suspend () -> Unit,
    private val sink: RunSink,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private enum class SearchEntry { Other, Ready, Draft }
    private var seq = 0
    private var pickupRun = false
    private var visualFailure = ""
    private var visualMiss: String? = null
    var lastRead: ToolExecution? = null
        private set
    /** 实际执行且核对成功的原路线下标，供交还模型时逐项说明。 */
    internal val matchedSteps = mutableListOf<Int>()
    /** 本轮输入后在后续页面再次读到的参数。仅交付这项证据，不让旧页面参与当前结果取值。 */
    internal var confirmedInputRead: JsonObject? = null
        private set
    /** 仅在点击后读到本轮新页标时交付入口证据，同时标明图形或文字来源。 */
    internal val checkedEntryReads = mutableListOf<JsonObject>()
    internal val confirmedPageLabels = linkedMapOf<String, JsonObject>()

    suspend fun run(choice: RouteChoice, fromAppEntry: Boolean = false, officialEndpoint: (ScreenSnapshot) -> Boolean = { false }): ReplayOutcome {
        pickupRun = LearnGate.privateCodeQuery(choice.route.template)
        val steps = choice.route.steps
        val total = steps.size
        val pinnedPackage = choice.route.packages.singleOrNull()?.takeIf { fromAppEntry }
        matchedSteps.clear()
        confirmedInputRead = null
        checkedEntryReads.clear()
        confirmedPageLabels.clear()
        var typedAt = -1
        var typedValue: String? = null
        fun event(i: Int, matched: Boolean, detail: String, skipped: Boolean = false) {
            if (matched && !skipped) matchedSteps += i
            emit(RunEvent.Replay(clock(), choice.route.id, i + 1, total, matched, if (pickupRun && matched) steps[i].tool else Redactor.text(detail), skipped = skipped))
        }

        val tabIndex = choice.route.navigationTabIndex ?: steps.lastIndex
        val lastTarget = steps.getOrNull(tabIndex)?.target
        val terminal = choice.route.terminalLabels - lastTarget?.name.orEmpty()
        // 只接续简单的两级页面入口。不跳过包含其他筛选、输入、参数或跨应用的步骤。
        val previousTarget = steps.getOrNull(tabIndex - 1)?.target
        val anonymousEntrance = previousTarget?.let { it.name.isEmpty() && it.resourceId == null && it.visualDescription == null } == true
        val canResume = fromAppEntry && tabIndex in 2 until total &&
            (choice.slots.isEmpty() || anonymousEntrance && choice.route.verifiedAnonymousEntrance) && choice.route.packages.size == 1 &&
            terminal.size >= 2 && lastTarget != null && RouteAnchors.stable(lastTarget.name) &&
            steps.subList(1, tabIndex + 1).all { tools.index[it.tool]?.verb == Verb.Tap && it.target?.requireVisualCheck != true } &&
            choice.route.hasNavigationTail(tabIndex)
        fun finalTab(s: ScreenSnapshot?): UiElement? {
            if (!canResume || s == null || s.packageName != choice.route.packages.single() ||
                s.protected || !s.hasWindow || s.changedDuringRead) return null
            val sel = requireNotNull(lastTarget)
            val names = s.elements.map { it.name.trim() }.toSet()
            if (terminal.all { it in names }) find(s, sel, choice.slots)?.let { return it }
            // 横向导航可能只显示部分相邻页签。同名父子节点共用中心时是同一入口；不同位置不猜。
            val matches = s.elements.filter { tab ->
                tab.name.trim() == sel.name && tab.bounds.size == 4 &&
                    s.elements.asSequence().filter { it.bounds.size == 4 && (it.bounds[1] + it.bounds[3]) / 2 in tab.bounds[1]..tab.bounds[3] }
                        .map { it.name.trim() }.filter { it in choice.route.navigationPeers }.distinct().take(2).count() == 2
            }
            if (matches.map { (it.bounds[0] + it.bounds[2]) / 2 to (it.bounds[1] + it.bounds[3]) / 2 }.distinct().size != 1) return null
            return matches.firstOrNull { it.role == sel.role && it.resourceId == sel.resourceId } ?: matches.firstOrNull { it.clickable }
        }
        fun atEndpoint(s: ScreenSnapshot?): Boolean = finalTab(s) != null &&
            terminal.all { label -> s!!.elements.any { it.name.trim() == label } }
        var resumed = false
        var i = 0
        // 冷启动有时已在探索时连续返回后的页面。只有当前包、页面文字和唯一入口都吻合才略过这些返回。
        val afterBacks = 1 + steps.drop(1).takeWhile { tools.verb(it.tool) == Verb.Back }.size
        fun atBackDestination(s: ScreenSnapshot?): Boolean {
            val next = steps.getOrNull(afterBacks) ?: return false
            val sel = next.target ?: return false
            if (!fromAppEntry || afterBacks <= 1 || s == null || !s.hasWindow || s.changedDuringRead || s.protected ||
                s.packageName != next.before.packageName || s.packageName != choice.route.packages.singleOrNull() ||
                find(s, sel, choice.slots) == null) return false
            val names = s.elements.map { it.name.trim() }.toSet()
            if (next.before.texts.count { it in names } >= 2) return true
            val peers = choice.route.entryButtonRow
            if (peers.distinct().size < 3 || !sel.uniqueAtRecord) return false
            return RouteAnchors.buttonRows(s.elements.asSequence().map { Triple(it.role, it.name, it.bounds) })
                .any { row -> row.filter { it in peers } == peers }
        }
        // 应用可能直接恢复至搜索编辑页。只略过打开搜索栏，输入及提交仍用本卡参数执行。
        val opener = steps.getOrNull(1)
        val input = steps.getOrNull(2)
        val submit = steps.getOrNull(3)
        fun searchEntry(s: ScreenSnapshot?): SearchEntry {
            if (pinnedPackage == null || s == null || s.packageName != pinnedPackage || !s.hasWindow || s.protected ||
                s.changedDuringRead || s.captureError != null || opener?.tool != "phone_tap" || input?.tool != "phone_type_text" ||
                submit?.tool != "phone_tap" || opener.target?.requireVisualCheck == true || opener.target?.name?.lowercase() !in SEARCH_ENTRANCES ||
                input.before.packageName != pinnedPackage || submit.before.packageName != pinnedPackage) return SearchEntry.Other
            val field = input.target?.takeIf { it.uniqueAtRecord }?.let { find(s, it, choice.slots) } ?: return SearchEntry.Other
            val button = submit.target?.takeIf { it.uniqueAtRecord }?.let { find(s, it, choice.slots) } ?: return SearchEntry.Other
            val text = (build(input, choice.slots)?.get("text") as? JsonPrimitive)?.content ?: return SearchEntry.Other
            val names = s.elements.map { it.name.trim() }.toSet()
            if (!field.editable || text !in choice.slots || !SEARCH_SUBMIT.matches(button.name.trim()) ||
                input.before.texts.count { it in names } < 2) return SearchEntry.Other
            val draft = s.elements.any { e -> e.editable && (e.secret || !e.hint && e.name.isNotBlank() &&
                (e.ref != field.ref || e.name.trim() != text)) }
            return if (draft) SearchEntry.Draft else SearchEntry.Ready
        }
        // 原目标有明确含义且有独立的点击后页标时，控件树缺项或同名歧义才可切到当前截图。
        fun visualFallback(step: RouteStep, s: ScreenSnapshot?): Boolean {
            val sel = step.target ?: return false
            return pinnedPackage != null && s != null && s.packageName == pinnedPackage && s.hasWindow &&
                !s.protected && !s.changedDuringRead && s.captureError == null && step.tool == "phone_tap" &&
                step.before.packageName == pinnedPackage && step.after.packageName == pinnedPackage &&
                (!sel.visualDescription.isNullOrBlank() || sel.name.isNotBlank()) && sel.bounds.size == 4 &&
                step.after.texts.distinct().count { label -> RouteAnchors.stable(label) && s.elements.none { it.name.trim() == label } } >= 2
        }
        fun visualPageContext(step: RouteStep, s: ScreenSnapshot?) = s != null &&
            s.elements.none { it.name.isNotBlank() } && step.before.texts.isNotEmpty() && visualFallback(step, s)
        // 原终点记录了空态和独立的业务页标时，普通启动可能已到此页。
        // 只略过最后一次导航；启动或前置步骤、所需参数以及动态结果仍由本轮实际读屏证明。
        fun atPickupEndpoint(step: RouteStep, s: ScreenSnapshot?): Boolean {
            if (!pickupRun || pinnedPackage == null || choice.slots.isNotEmpty() || step !== steps.last() || step.target?.requireVisualCheck == true ||
                step.tool != "phone_tap" || step.after.packageName != pinnedPackage || s == null || s.packageName != pinnedPackage ||
                !s.hasWindow || s.protected || s.changedDuringRead || s.captureError != null) return false
            val labels = s.elements.filterNot { it.editable || it.secret || it.hint }.map { it.name.trim() }.toSet()
            return step.after.texts.any { LearnGate.isEmptyPickupLabel(it) && it in labels } &&
                (officialEndpoint(s) || step.after.texts.any { it !in step.before.texts && it != step.target?.name && !LearnGate.isEmptyPickupLabel(it) && it in labels }) &&
                labels.none { RouteAnchors.requiresUserConsent(it) || it.contains("登录") || it.contains("加载中") || LearnGate.hasPickupCode(it) }
        }
        var resumedSearch = false
        var skippedBacks = 0
        var expiredReads = 0
        while (i < total) {
            val step = steps[i]
            fun diverged(why: String, seen: String, executed: Boolean = false, error: String? = null): ReplayOutcome {
                event(i, false, why)
                return ReplayOutcome.Diverged(i, (if (pickupRun) step.tool else describe(step, choice.slots)) + " ($why)", seen, executed, error)
            }
            if (step.target?.name?.let(RouteAnchors::requiresUserConsent) == true)
                return diverged("需要用户亲自同意条款或授权", "")
            val verb = tools.index[step.tool]?.takeIf { it.routable }?.verb ?: return diverged("not a replayable action", "")
            // 锁屏、被拿走时先等;等完再读屏,核对和执行都按等完之后的一屏(执行入口不再等第二次)
            gate()
            // 新虚拟屏在启动首个应用前没有元素树；按明确包名启动本身不依赖前一屏。
            val opening = fromAppEntry && i == 0 && verb == Verb.LaunchApp && step.target == null
            var observed = if (opening) null else read() ?: return diverged("the screen could not be read", "")
            // 启动就绪以后页面也可能再次刷新。只有这一份稳定读屏上的编号才能送去执行。
            // 搜索编辑页切换窗口时，包名可能先就绪，输入框和控件树稍后才到。
            fun awaitingTree(page: ScreenSnapshot?) = page?.changedDuringRead == true ||
                (verb == Verb.TypeText && pinnedPackage != null && page?.packageName == pinnedPackage &&
                    !page.protected && page.elements.none { it.editable || it.name.isNotBlank() })
            if (fromAppEntry && awaitingTree(observed)) {
                withTimeoutOrNull(ENTRY_WAIT_MS) {
                    while (awaitingTree(observed)) {
                        delay(ENTRY_POLL_MS)
                        gate()
                        observed = read()
                    }
                }
                if (observed == null || awaitingTree(observed))
                    return diverged("the page did not settle before the action", observed?.let(::summary).orEmpty())
            }
            val snap = observed
            if (fromAppEntry && snap?.elements?.any { it.clickable && RouteAnchors.requiresUserConsent(it.name) } == true)
                return diverged("当前页面需要用户亲自同意条款或授权", snap.packageName.orEmpty())
            if (snap?.protected == true) return diverged("the page is protected", PROTECTED_SEEN)
            if (atPickupEndpoint(step, snap)) {
                gate()
                if (!atPickupEndpoint(step, read())) return diverged("本次重读未能确认原待取页面与空态", pinnedPackage.orEmpty())
                event(i, true, "本次两次读屏已核实待取页面和空态，略过末步导航", skipped = true)
                i++
                continue
            }
            if (i == tabIndex - 1 && finalTab(snap) != null) {
                event(i, true, "当前画面已出现导航步骤的目标页签，直接重读并核实该入口", skipped = true)
                resumed = true
                i++
                continue
            }
            val seen = snap?.let(::summary).orEmpty()
            if (!opening) step.before.packageName?.let { if (snap?.packageName != it) return diverged("the foreground app is not $it", seen) }
            val names = snap?.elements.orEmpty().map { it.name.trim() }.toSet()
            val need = minOf(2, step.before.texts.size)
            if (resumedSearch && i == 2) {
                if (searchEntry(snap) != SearchEntry.Ready) return diverged("the restored search field changed before input", seen)
            } else if (skippedBacks > 0 && i == afterBacks) {
                if (!atBackDestination(snap)) return diverged("the entry page changed before the action", seen)
            } else if (!opening && !(resumed && i == tabIndex) && step.before.texts.count { it in names } < need &&
                !visualPageContext(step, snap)) return diverged("当前页面与录制这一步时的页面不一致", seen)

            // 详情里的固定对象标题可能在打开子页后消失。只保留本轮实际到达且与确认口径相符的页标。
            if (!pickupRun && i - 1 in matchedSteps && pinnedPackage != null && snap?.packageName == pinnedPackage &&
                snap.hasWindow && !snap.changedDuringRead && snap.captureError == null) {
                val arrival = steps[i - 1].after
                for (label in step.before.texts) {
                    if (label in confirmedPageLabels || label !in arrival.texts || !RouteAnchors.stable(label) ||
                        label !in choice.confirmedScope || snap.elements.none { !it.secret && !it.editable && !it.hint && it.name.trim() == label }) continue
                    tools.noteReplayRead(pinnedPackage, label)?.let { observation ->
                        confirmedPageLabels[label] = buildJsonObject {
                            put("beforeStep", JsonPrimitive(i + 1))
                            put("app", JsonPrimitive(pinnedPackage))
                            put("visibleText", JsonPrimitive(observation.text))
                            put("observationId", JsonPrimitive(observation.id))
                        }
                    }
                }
            }
            // 筛选面板常遮住搜索词。先完成输入与下一次点击，再以打开面板前的稳定页面回读参数。
            val value = typedValue
            if (pinnedPackage != null && value != null && i > typedAt + 1 && verb == Verb.Tap &&
                tools.verb(steps[i - 1].tool) == Verb.Tap && snap?.packageName == pinnedPackage &&
                snap.hasWindow && !snap.changedDuringRead && snap.captureError == null && snap.elements.none { it.editable && it.focused } &&
                snap.elements.any { !it.secret && !it.editable && !it.hint && it.name.trim() == value }) {
                tools.noteReplayRead(pinnedPackage, value)?.let { observed ->
                    confirmedInputRead = JsonObject(mapOf("beforeStep" to JsonPrimitive(i + 1), "app" to JsonPrimitive(pinnedPackage),
                        "snapshotId" to JsonPrimitive(snap.snapshotId), "visibleText" to JsonPrimitive(observed.text), "observationId" to JsonPrimitive(observed.id)))
                    emit(RunEvent.State(clock(), "replay_input_read", "第 ${i + 1} 步前已在 $pinnedPackage 回读本次输入，证据 ${observed.id}"))
                }
            }
            val args = build(step, choice.slots) ?: return diverged("the slots in the arguments cannot be filled", seen)
            val target = step.target?.let { sel ->
                if (resumed && i == tabIndex) finalTab(snap) else snap?.let { find(it, sel, choice.slots) }
            }
            val checkpoint = step.target?.requireVisualCheck == true
            if (checkpoint && !visualFallback(step, snap)) return diverged("当前页面无法建立截图与点击后页标的对照", seen)
            val bySight = checkpoint || !resumed && (visualPageContext(step, snap) || target == null && visualFallback(step, snap))
            if (target == null && !bySight && (step.target != null || verb == Verb.Tap))
                return diverged("当前页面无法唯一定位这一步的控件", seen)
            // 链接只开参考资料里此刻还在的:资料改过,路线里记着的链接就不再放行
            if (verb == Verb.OpenLink) {
                fun arg(k: String) = (args[k] as? JsonPrimitive)?.contentOrNull
                if (!tools.linkAllowed(arg("action"), arg("uri"), arg("package"))) return diverged("the link is no longer in the reference material", seen)
            }
            val description = step.target?.visualDescription
            var entryEvidence: String? = null
            var usedTextAlternative = false
            var beforeActionNames = names
            val call = if (bySight) {
                val sel = requireNotNull(step.target)
                val what = fill(description ?: "找到当前页面唯一的「${sel.name}」控件", choice.slots)
                    ?: return diverged("visual target parameter missing", seen)
                // 强制从本次像素定位；当前目标的可见文字可作旁证，不能替代框选复核。
                val context = if (visualPageContext(step, snap)) "仅当同一张截图中也能看见${step.before.texts.take(2).joinToString("、")}时定位，否则返回未找到。" else ""
                val request = what + context
                if (request.length > 120) return diverged("视觉目标及必要页标超过定位工具的长度上限", seen)
                val label = target?.name?.takeIf { sel.name.isNotBlank() && it.isNotBlank() }
                verifiedVisualRef(step.tool, request, target?.bounds ?: sel.bounds, label, pixelsOnly = true, onSeen = { appearance ->
                    entryEvidence = "点击前截图的查找条件「$request」已通过框选复核。" + appearance?.let { "画面所见「$it」。" }.orEmpty()
                }) ?: run missed@{
                    // 仅在当次像素识别确实失败、用户确认口径明示备用方式时，重新读屏选择文字编号。
                    var blocked = ""
                    if (checkpoint) {
                        val alternative = freshTextAlternative(step, sel, target, requireNotNull(pinnedPackage),
                            choice.confirmedScope) { blocked = "文字备用未采用：$it。" }
                        if (alternative != null) {
                            val (current, button) = alternative
                            beforeActionNames = current.elements.map { it.name.trim() }.toSet()
                            usedTextAlternative = true
                            entryEvidence = "本次截图定位$visualFailure，图形未核实，以已核实的文字入口「${button.name}」打开。"
                            return@missed buildJsonObject {
                                put("ref", JsonPrimitive(button.ref))
                                put("snapshotId", JsonPrimitive(current.snapshotId))
                            }
                        }
                    }
                    val nodes = snap?.elements.orEmpty()
                    val expectedName = fill(sel.name, choice.slots).orEmpty()
                    val equalName = nodes.filter { expectedName.isNotEmpty() && it.name.trim() == expectedName }
                    val matches = nodes.count { it.role == sel.role && it.resourceId == sel.resourceId && (expectedName.isEmpty() || it.name.trim() == expectedName) }
                    val role = sel.role.takeIf { Regex("[A-Za-z][A-Za-z0-9_.]{0,60}").matches(it) } ?: "控件"
                    val kind = if (description == null) "名称" else "录制说明"
                    val info = "现场视觉未确认入口（$visualFailure）。$blocked$role ${sel.bounds}，$kind；同名 ${equalName.size}，完整匹配 $matches，" +
                        "前页标 ${step.before.texts.count { it in names }}/${step.before.texts.size}，终页标 ${step.after.texts.count { it in names }}/${step.after.texts.size}，" +
                        "有空态 ${nodes.any { !it.editable && !it.hint && !it.secret && LearnGate.isEmptyPickupLabel(it.name) }}。"
                    return diverged(info, pinnedPackage.orEmpty())
                }
            } else if (description != null && target != null && verb == Verb.Tap) {
                // 普通录制说明可按控件名重新定位；这种命中只供点击，不作为图标外观的证据。
                verifiedVisualRef(step.tool, fill(description, choice.slots) ?: return diverged("visual target parameter missing", seen), target.bounds, target.name.takeIf { step.target?.name?.isNotBlank() == true && it.isNotBlank() })
                    ?: return diverged("visual result does not match the current control", seen)
            } else target?.let {
                // 原点击可能用坐标或名称定位。已从当前屏核对目标后，工具只接受这一份新编号。
                val resolved = if (verb == Verb.Tap) args - setOf("x", "y", "element", "role") else args
                JsonObject(resolved + mapOf("ref" to JsonPrimitive(it.ref), "snapshotId" to JsonPrimitive(requireNotNull(snap).snapshotId)))
            } ?: args

            val id = "replay-${++seq}"
            val out = when (val o = executor.run(Part.ToolCall(id, step.tool, call.toString()), Origin.Replay)) {
                is ActionOutcome.Ran -> o
                is ActionOutcome.Refused -> when (o.code) {
                    "guard_stopped", "guard_warning" -> return diverged("the action guard raised a warning", seen)
                    "confirmation_required", "confirmation_mismatch" -> {
                        event(i, false, "irreversible action, handed back to the model for confirmation")
                        return ReplayOutcome.StoppedForConfirmation(i)
                    }
                    "target_unresolved" -> return diverged("cannot tell which element the tap would hit", seen)
                    "own_app" -> return diverged("it acts on Mika itself", seen)
                    // #217:非 Ui/无人值守来源回放到付款步,审批策略自动拒绝——这轮永远等
                    // 不来同意,不是「等确认」,交还模型向用户说明后按未完成收尾。
                    "approval_unavailable" -> return diverged(
                        "this run cannot get approval for a payment step", seen,
                        error = "approval_unavailable",
                    )
                    else -> error("unexpected refusal ${o.code}")
                }
                is ActionOutcome.Cancelled -> return diverged("cancelled", seen)
            }
            val exec = out.exec
            // 动作后只读一屏:路线锚点和「有没有出现预期文字」都用它
            var fresh = out.seen ?: tools.interfaces.peek()
            val effective = effectiveWrite(verb, exec.content)
            val now = fresh?.let { if (it.protected) PROTECTED_SEEN else summary(it) } ?: seen
            if (exec.outcome != null || exec.content["ok"] != JsonPrimitive(true)) {
                val error = (exec.content["error"] as? JsonPrimitive)?.contentOrNull
                // Android 在注入点击前返回 ref_expired。刷新同一目标的快照并重做全部前置核对。
                if (fromAppEntry && verb == Verb.Tap && exec.outcome == null && error == "ref_expired" && expiredReads < 2) {
                    expiredReads++
                    delay(ENTRY_POLL_MS)
                    continue
                }
                return diverged("the action failed" + error?.let { ": $it" }.orEmpty(), now, executed = exec.outcome != null || error !in NOT_EXECUTED, error = error)
            }
            val switched = (exec.content["app_switched"] as? JsonPrimitive)?.contentOrNull
            if (pinnedPackage != null && switched != null)
                return diverged("the task display left $pinnedPackage", switched, executed = true)
            if (fromAppEntry && verb == Verb.LaunchApp) {
                val pkg = (args["package"] as? JsonPrimitive)?.contentOrNull
                    ?: return diverged("launch has no target package", now, executed = true)
                val next = steps.getOrNull(i + 1)?.takeIf { it.before.packageName == pkg }
                // 系统已接受启动时，无障碍窗口仍可能在切换。只等当前任务屏可读，不重发启动或点击。
                fun ready(s: ScreenSnapshot?, waitForNamedEntry: Boolean = false): Boolean {
                    if (s == null || s.packageName != pkg || !s.hasWindow || s.protected || s.changedDuringRead || s.captureError != null) return false
                    if (next != null && (atPickupEndpoint(next, s) || !waitForNamedEntry && visualPageContext(next, s))) return true
                    if (s.elements.isEmpty()) return false
                    if (next == null || tabIndex == 2 && finalTab(s) != null || i == 0 && (atBackDestination(s) || searchEntry(s) != SearchEntry.Other)) return true
                    val labels = s.elements.map { it.name.trim() }.toSet()
                    val target = next.target
                    val entryFound = target == null || find(s, target, choice.slots) != null
                    // 缺少首页页标时，启动广告也会满足“可以看图”的条件。先让有名称的入口随业务页出现。
                    if (waitForNamedEntry && !entryFound && !target?.name.isNullOrBlank() && next.before.texts.isEmpty()) return false
                    return next.before.texts.count { it in labels } >= minOf(2, next.before.texts.size) &&
                        (entryFound || visualFallback(next, s))
                }
                // peek 只用于记录；用可供下一步解析元素编号的正式快照判定就绪。
                fun awaitingEntry(s: ScreenSnapshot?): Boolean {
                    if (s?.protected == true || ready(s, waitForNamedEntry = true)) return false
                    // 已认出上次停留的内页，直接进入原有的逐页返回流程。
                    return s == null || s.changedDuringRead || s.captureError != null || i != 0 || pkg != pinnedPackage ||
                        next?.target?.uniqueAtRecord != true || restoredPage(s, pkg, steps, choice.slots, fromEndpoint = false) == null
                }
                fresh = read()
                if (awaitingEntry(fresh)) withTimeoutOrNull(ENTRY_WAIT_MS) {
                    while (awaitingEntry(fresh)) {
                        delay(ENTRY_POLL_MS)
                        gate()
                        fresh = read()
                    }
                }
                if (fresh?.protected == true) return diverged("the page is protected", PROTECTED_SEEN, executed = true)
                // 启动恢复了已走过的内页时，先退出到记录的入口；随后从第一项点击重新执行，包括所有参数。
                val entryTarget = next?.target
                if (i == 0 && pkg == pinnedPackage && !ready(fresh, waitForNamedEntry = true) && next != null && tools.verb(next.tool) == Verb.Tap &&
                    entryTarget?.uniqueAtRecord == true && entryTarget.name.isNotBlank()) {
                    val back = tools.index.nameOf(Verb.Back, tools.surface)
                    var previous = steps.size + 1
                    var count = 0
                    while (back != null && count < steps.size) {
                        gate()
                        fresh = read()
                        if (ready(fresh, waitForNamedEntry = true)) break
                        val current = fresh ?: break
                        val position = restoredPage(current, pkg, steps, choice.slots, fromEndpoint = previous == steps.size) ?: break
                        if (position >= previous) break
                        previous = position
                        val result = executor.run(Part.ToolCall("replay-entry-back-${++seq}", back, "{}"), Origin.Replay)
                        count++
                        val response = (result as? ActionOutcome.Ran)?.exec
                        val location = if (position == steps.size) "原路线终点页" else "原路线第 ${position + 1} 步所在内页"
                        emit(RunEvent.State(clock(), "entry_recovery", "${location}，尝试第 ${count} 次返回入口"))
                        if (response?.content?.get("ok") != JsonPrimitive(true) || response.outcome != null ||
                            response.content["changed"] != JsonPrimitive(true) || response.content["app_switched"] != null)
                            return diverged("could not return from the restored page", pkg, executed = true)
                        gate()
                        fresh = read()
                        if (ready(fresh, waitForNamedEntry = true) || fresh?.packageName != pkg || fresh?.protected == true) break
                    }
                }
                val search = if (i == 0) searchEntry(fresh) else SearchEntry.Other
                if (search == SearchEntry.Draft) return diverged("恢复的搜索页有其他待编辑内容", fresh?.let(::summary).orEmpty(), executed = true)
                if (search == SearchEntry.Ready) {
                    event(0, true, describe(step, choice.slots))
                    event(1, true, "已在已核对的搜索编辑页，直接重新输入本卡参数", skipped = true)
                    resumedSearch = true
                    i = 2
                    continue
                }
                if (i == 0 && atBackDestination(fresh)) {
                    event(i, true, "已在连续返回后的页面，现场核实下一步入口")
                    for (skip in 1 until afterBacks) event(skip, true, "已从当前业务入口核实连续返回的终点", skipped = true)
                    skippedBacks = afterBacks - 1
                    i = afterBacks
                    continue
                }
                if (i == 0 && tabIndex == 2 && finalTab(fresh) != null) {
                    // 可见不等于选中。仍然按新快照重做末步，确保用户要求的页签被选中。
                    event(i, true, "已定位同一组导航页签，接续验证末步入口")
                    resumed = true
                    for (skip in 1 until tabIndex) event(skip, true, "已从同组页签核实导航入口", skipped = true)
                    i = tabIndex
                    continue
                }
                if (!ready(fresh)) return diverged("启动后未找到路线入口${next?.target?.name?.takeIf(String::isNotBlank)?.let { "「$it」" }.orEmpty()}，目标应用 $pkg，当前应用 ${fresh?.packageName ?: "未识别"}",
                    fresh?.let(::summary).orEmpty(), executed = true)
            }
            if (pinnedPackage != null && fresh?.packageName?.let { it != pinnedPackage } == true)
                return diverged("the task display left $pinnedPackage", fresh?.packageName.orEmpty(), executed = true)
            val shown = fresh?.elements?.map { it.name.trim() }?.toSet().orEmpty()
            val landed = exec.content["changed"] == JsonPrimitive(true) || exec.content["verification"] == JsonPrimitive("match") ||
                (effective && verb in OPENS) ||
                step.after.texts.any { it in shown }
            val verifyTab = resumed && i == tabIndex
            val mustSeeSelection = verifyTab && choice.route.requireLiveTabEvidence && terminal.all { it in names }
            if (!verifyTab && !landed) return diverged("no expected change after the action", now, executed = true)
            if (bySight || verifyTab || pinnedPackage != null && i == steps.lastIndex) {
                // 卡片交付使用这份已核对应用归属的读屏；已选中页签还要核对终点标签。
                fun verified(s: ScreenSnapshot?) = if (resumed) atEndpoint(s) && (!mustSeeSelection ||
                    finalTab(s)?.states?.contains("selected") == true) else s != null &&
                    s.packageName == pinnedPackage && s.hasWindow && !s.protected && !s.changedDuringRead && s.captureError == null &&
                    (!bySight || step.after.texts.filterNot { it in beforeActionNames }.let { expected ->
                        expected.count { label -> s.elements.any { it.name.trim() == label } } >= 2
                    })
                var end = if (verb == Verb.LaunchApp) fresh else read()
                withTimeoutOrNull(ENTRY_WAIT_MS) {
                    while (!verified(end) && end?.protected != true && (end?.packageName == null || end?.packageName == pinnedPackage)) {
                        delay(ENTRY_POLL_MS)
                        gate()
                        end = read()
                    }
                }
                if (!verified(end)) {
                    if (usedTextAlternative) return diverged("文字备用入口未出现两个预期的新页标", end?.let(::summary).orEmpty(), executed = true)
                    if (bySight) return diverged("the visually located action did not reach the recorded page", end?.let(::summary).orEmpty(), executed = true)
                    // 部分页签只用下划线或底色表示选中。仅核实现场状态，不补点；看图后重新核对包、同行入口和终点字段。
                    val tab = finalTab(end)?.takeIf { mustSeeSelection && atEndpoint(end) }
                    val proof = tab?.let { verifiedVisualRef(step.tool,
                        "在同行页签中找当前被选中的「${it.name}」。必须看见该项的选中下划线、强调色或选中底色，相邻项未选中；仅看见名称请返回未找到。", it.bounds) }
                    if (proof != null) end = read()
                    if (proof == null || !atEndpoint(end) || finalTab(end)?.bounds != tab?.bounds)
                        return diverged("the result page could not be verified in $pinnedPackage", end?.let(::summary).orEmpty(), executed = true)
                    emit(RunEvent.State(clock(), "tab_selection_visual", "第 ${i + 1} 步已用当前截图核实选中页签"))
                }
                if (usedTextAlternative) emit(RunEvent.State(clock(), "replay_text_alternative", "第 ${i + 1} 步图形未核实，已从当次文字入口打开并读到目标页标。"))
                else if (bySight) emit(RunEvent.State(clock(), "replay_visual_fallback", "第 ${i + 1} 步经当前截图定位，并已读到预期页面标识。"))
                if (checkpoint) {
                    val labels = end?.elements.orEmpty().map { it.name.trim() }.toSet()
                    val arrived = step.after.texts.filter { it !in beforeActionNames && it in labels }
                    val text = requireNotNull(entryEvidence) + "点击该处后新出现「${arrived.joinToString("」「")}」。"
                    val source = if (usedTextAlternative) "elements" else "vision"
                    tools.noteReplayRead(requireNotNull(pinnedPackage), text, entrySource = source)?.let {
                        checkedEntryReads += buildJsonObject {
                            put("step", JsonPrimitive(i + 1))
                            put("source", JsonPrimitive(source))
                            if (usedTextAlternative) put("visualMiss", JsonPrimitive(visualMiss))
                            put("observationId", JsonPrimitive(it.id))
                            put("evidence", JsonPrimitive(it.text))
                        }
                    }
                }
            }
            if (verb == Verb.TypeText) {
                confirmedInputRead = null
                typedAt = i
                typedValue = (args["text"] as? JsonPrimitive)?.contentOrNull?.takeIf { it in choice.slots &&
                    exec.content["secret"] == JsonPrimitive(false) && exec.content["verification"] == JsonPrimitive("match") }
            }
            event(i, true, describe(step, choice.slots))
            i++
            expiredReads = 0
        }
        return ReplayOutcome.Completed(matchedSteps.size, resumedAtLastTap = resumed && tabIndex == steps.lastIndex)
    }

    /** 核对现场定位点与目标边界；要求像素证据时，只接受视觉模型框选和复核的结果。 */
    private suspend fun verifiedVisualRef(tool: String, description: String, bounds: List<Int>, label: String? = null, pixelsOnly: Boolean = false,
                                          onSeen: (String?) -> Unit = {}): JsonObject? {
        visualFailure = "定位工具不可用"
        visualMiss = null
        val locate = tools.index.nameOf(Verb.FindVisually, tools.surfaceOf(tool)) ?: return null
        val query = buildJsonObject {
            put("target", JsonPrimitive(description))
            label?.let { put("text", JsonPrimitive(it)) }
            if (pixelsOnly) put("requirePixels", JsonPrimitive(true))
        }
        val located = executor.run(Part.ToolCall("replay-find-${++seq}", locate, query.toString()), Origin.Replay)
        val hit = (located as? ActionOutcome.Ran)?.exec?.content ?: return null
        val x = (hit["x"] as? JsonPrimitive)?.content?.toIntOrNull()
        val y = (hit["y"] as? JsonPrimitive)?.content?.toIntOrNull()
        val ref = (hit["ref"] as? JsonPrimitive)?.content
        val sid = hit["snapshotId"]
        if (hit["found"] != JsonPrimitive(true)) {
            val reason = ((hit["reason"] ?: hit["error"]) as? JsonPrimitive)?.contentOrNull
            visualMiss = reason?.takeIf { it in setOf("not_found", "not_confirmed", "name_mismatch", "unverified_grounding") }
            visualFailure = when (reason) {
                "not_found" -> "画面中未找到"
                "not_confirmed", "name_mismatch", "unverified_grounding" -> "复核不一致"
                "protected_content" -> "页面受保护"
                "vision_unavailable" -> "看图服务不可用"
                else -> "未返回可靠定位"
            }
            return null
        }
        visualFailure = "未通过像素或区域核对"
        if (pixelsOnly && hit["source"] != JsonPrimitive("vision")) return null
        if (hit["found"] != JsonPrimitive(true) || hit["unverified"] == JsonPrimitive(true) || ref?.startsWith("@v") != true ||
            sid == null || bounds.size != 4 || x == null || y == null || x !in bounds[0]..bounds[2] || y !in bounds[1]..bounds[3]) return null
        onSeen((hit["seen"] as? JsonPrimitive)?.contentOrNull)
        return JsonObject(mapOf("ref" to JsonPrimitive(ref), "snapshotId" to sid))
    }

    /** 视觉调用期间页面可能已切换。按新读屏核对业务页、固定导航控件及其同名文字的区域。 */
    private suspend fun freshTextAlternative(step: RouteStep, sel: Selector, original: UiElement?, pkg: String,
                                             scope: String, onMiss: (String) -> Unit): Pair<ScreenSnapshot, UiElement>? {
        fun reject(reason: String): Nothing? { onMiss(reason); return null }
        if (visualMiss == null) return reject("本次定位失败原因不适用备用入口")
        if (!TEXT_ALTERNATIVE.containsMatchIn(scope)) return reject("本次口径未明确允许文字备用入口")
        if (original == null) return reject("首次读屏未能唯一定位录制的文字控件")
        if (sel.name !in RouteAnchors.navigationLabels) return reject("目标不是固定导航词")
        if (original.bounds.size != 4) return reject("首次读屏缺少入口边界")
        val current = read() ?: return reject("无法重读当前页面")
        if (current.packageName != pkg) return reject("重读时已离开目标应用")
        if (current.protected) return reject("重读时页面受保护")
        if (!current.hasWindow || current.changedDuringRead || current.captureError != null) return reject("重读时没有稳定的窗口")
        val names = current.elements.map { it.name.trim() }.toSet()
        if (step.before.texts.count { it in names } < minOf(2, step.before.texts.size)) return reject("重读时原页面标识不足")
        if (step.after.texts.distinct().count { it !in names } < 2) return reject("缺少两个可区分点击前后的页标")
        val sameName = current.elements.filter { it.name.trim() == sel.name }
        val matches = sameName.filter { it.role == sel.role && it.resourceId == sel.resourceId }
        val button = matches.singleOrNull() ?: return reject("重读后完整匹配控件 ${matches.size} 个")
        val bounds = original.bounds
        if (!button.clickable || button.editable || button.secret) return reject("重读控件不是可点击的导航入口")
        if (button.bounds != bounds) return reject("重读时入口边界已变化")
        // Android 的 centerX/Y 以物理像素记录；父子节点统一用 0–999 边界计算中心。
        if (sameName.any { it.bounds.size != 4 }) return reject("同名节点缺少边界")
        if (sameName.any { (it.bounds[0] + it.bounds[2]) / 2 !in bounds[0]..bounds[2] ||
                (it.bounds[1] + it.bounds[3]) / 2 !in bounds[1]..bounds[3] }) return reject("其他同名节点位于入口区域以外")
        return current to button
    }

    private suspend fun read(): ScreenSnapshot? = try {
        // 读路线此刻所在的那块屏(最近一步动作的那块)
        val dump = tools.index.nameOf(Verb.ListElements, tools.surface)!!
        val r = tools.executeRead(Part.ToolCall("replay-read-${++seq}", dump, "{}"), 0)
        lastRead = r
        (r.content["snapshotId"] as? JsonPrimitive)?.contentOrNull?.let { tools.interfaces.snapshotFor(tools.declared(dump), JsonObject(mapOf("snapshotId" to JsonPrimitive(it)))) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** 用后续步骤的独立锚点及唯一控件认出恢复的内页。顶部返回位置有歧义、对话框或已填表单时停下。 */
    private fun restoredPage(s: ScreenSnapshot, pkg: String, steps: List<RouteStep>, slots: List<String>, fromEndpoint: Boolean): Int? {
        if (s.packageName != pkg || !s.hasWindow || s.protected || s.changedDuringRead || s.captureError != null ||
            s.windows.count { it.type == "application" } > 1 ||
            s.elements.any { it.secret || it.editable && !it.hint && it.name.isNotBlank() ||
                it.role.contains("dialog", true) || it.name.trim().lowercase() in CONFIRM_CONTROLS }) return null
        val back = s.elements.filter { e -> e.name.trim().lowercase() in BACK_LABELS && e.bounds.size == 4 &&
            cy(e.bounds) <= 150 && cx(e.bounds) <= 250 }
        // 无障碍树可以分别列出工具栏、返回容器和文字。系统返回只需证明页面层级，不点击这些节点。
        val up = back.minByOrNull { (it.bounds[2] - it.bounds[0]) * (it.bounds[3] - it.bounds[1]) }
        if (up != null && back.all { cx(up.bounds).toInt() in it.bounds[0]..it.bounds[2] &&
                cy(up.bounds).toInt() in it.bounds[1]..it.bounds[3] } &&
            s.elements.none { it.editable || RouteAnchors.requiresUserConsent(it.name) }) {
            val header = s.elements.filter { it.bounds.size == 4 && it.bounds[0] >= up.bounds[2] &&
                cy(up.bounds).toInt() in it.bounds[1]..it.bounds[3] && cy(it.bounds).toInt() in up.bounds[1]..up.bounds[3] }
            val last = steps.last()
            val tabs = last.target?.name.orEmpty().split('/', '／', '|').map(String::trim)
            if (tabs.size >= 2 && tabs.distinct().size == tabs.size && tabs.all { label ->
                    label.isNotEmpty() && label in last.after.texts && header.any { it.name == label &&
                        (it.role.contains("tab", true) || it.role.contains("radio", true)) }
                }) return steps.size
            val headers = header.map { it.name.trim() }.toSet()
            val pages = (2 until steps.size).filter { index ->
                val arrived = steps[index - 1].after
                val before = steps[index].before
                arrived.packageName == pkg && before.packageName == pkg && before.texts.distinct().count {
                    it.lowercase() !in BACK_LABELS && it in arrived.texts && it in headers
                } >= 2
            }
            pages.singleOrNull()?.let { return it }
        }
        if (back.none { it.clickable } || back.map { cx(it.bounds) to cy(it.bounds) }.distinct().size != 1) return null
        // 已打开的详情页把末次点击的固定名称显示为返回键旁的标题。只用它辨认该往回走，随后照常重做路线。
        val last = steps.last()
        val target = last.target
        if (tools.verb(last.tool) == Verb.Tap && last.before.packageName == pkg && last.after.packageName == pkg &&
            target?.uniqueAtRecord == true && target.requireVisualCheck != true && RouteAnchors.stable(target.name) && !SLOT.containsMatchIn(target.name)) {
            val named = s.elements.filter { it.name.trim() == target.name }
            val backBox = back.first { it.clickable }.bounds
            val headings = named.filter { e -> e.bounds.size == 4 && !e.clickable && !e.editable &&
                e.role.lowercase() in setOf("textview", "text", "heading") && e.bounds[0] >= backBox[2] &&
                cy(backBox).toInt() in e.bounds[1]..e.bounds[3] && cy(e.bounds).toInt() in backBox[1]..backBox[3] }
            if (headings.isNotEmpty()) {
                val title = headings.singleOrNull() ?: return null
                if (named.any { it !== title && !(it.role.equals("WebView", true) && !it.clickable &&
                        it.bounds.size == 4 && it.bounds[0] <= backBox[0] && it.bounds[2] >= title.bounds[2] &&
                        it.bounds[1] <= title.bounds[1] && it.bounds[3] >= title.bounds[3]) } ||
                    s.elements.any { it.editable || RouteAnchors.requiresUserConsent(it.name) }) return null
                return steps.size
            }
        }
        val names = s.elements.map { it.name.trim() }.toSet()
        return (2 until steps.size).firstOrNull { index ->
            val step = steps[index]
            val selector = step.target
            step.before.packageName == pkg && selector?.uniqueAtRecord == true && step.before.texts.any {
                it in names && (!fromEndpoint || it.lowercase() !in BACK_LABELS && it != selector.name)
            } &&
                (selector.name.isNotBlank() || tools.verb(step.tool) == Verb.TypeText && step.before.texts.count { it in names } >= 2) &&
                find(s, selector, slots) != null
        }
    }

    /** 去掉编号;每个字符串参数里的 `{n}` 都换成槽值。槽号超出范围、或非字符串参数里带着槽,为 null。 */
    private fun build(step: RouteStep, slots: List<String>): JsonObject? {
        val o = try { Json.parseToJsonElement(step.argsJson) as? JsonObject } catch (e: Exception) { null } ?: return null
        val out = mutableMapOf<String, JsonElement>()
        for ((k, v) in o) {
            if (k == "ref" || k == "snapshotId" || k == "target") continue
            out[k] = when {
                v is JsonPrimitive && v.isString -> JsonPrimitive(fill(v.content, slots) ?: return null)
                SLOT.containsMatchIn(v.toString()) -> return null
                else -> v
            }
        }
        return JsonObject(out)
    }

    /**
     * 在当前屏里按选择器找唯一的目标。名字为空不比名字;`{n}` 先换成槽值。
     * 记录时唯一、现在却不止一个:页面已不是那时的样子(如列表里多了同名条目),不认。
     * 记录时就不唯一:取同序号的那个,且只有它离记录时的位置不超过屏宽 15% 才认。
     */
    private fun find(s: ScreenSnapshot, sel: Selector, slots: List<String>): UiElement? {
        sel.productLabel?.let { label ->
            val matches = s.elements.filter { !it.secret && !it.editable && it.role == sel.role &&
                it.bounds.size == 4 && it.name.trim().startsWith(label, ignoreCase = true) }
            // 结果常同时暴露同一商品行及其内层。多个独立商品行同时匹配时等待重新定位。
            val inner = matches.minByOrNull { (it.bounds[2] - it.bounds[0]).toLong() * (it.bounds[3] - it.bounds[1]) } ?: return null
            return inner.takeIf { matches.all { e -> e.bounds[0] <= inner.bounds[0] && e.bounds[1] <= inner.bounds[1] &&
                e.bounds[2] >= inner.bounds[2] && e.bounds[3] >= inner.bounds[3] } }
        }
        val name = fill(sel.name, slots) ?: return null
        val same = s.elements.filter { it.role == sel.role && it.resourceId == sel.resourceId && (name.isEmpty() || it.name.trim() == name) }
        if (same.size == 1) return same.single()
        // 控件类型或资源 ID 变了时，允许唯一可见的同名控件作候选。真正点击仍须现场定位并命中该控件边界。
        if (same.isEmpty() && sel.visualDescription != null && name.isNotBlank())
            return s.elements.singleOrNull { !it.secret && !it.editable && it.name.trim() == name && it.bounds.size == 4 }
        if (same.isEmpty() || sel.uniqueAtRecord || sel.bounds.size != 4) return null
        fun near(e: UiElement) = e.bounds.size == 4 && hypot(cx(e.bounds) - cx(sel.bounds), cy(e.bounds) - cy(sel.bounds)) <= NEAR
        val pick = same.getOrNull(sel.ordinal)?.takeIf(::near) ?: return null
        return pick.takeIf { same.count(::near) == 1 }
    }

    private fun cx(b: List<Int>) = (b[0] + b[2]) / 2.0
    private fun cy(b: List<Int>) = (b[1] + b[3]) / 2.0

    private fun summary(s: ScreenSnapshot): String {
        val names = s.elements.filterNot { it.secret || it.editable }.map { it.name.trim() }.filter(String::isNotEmpty)
        // 码可能单独放在标签下一行，取件页面的诊断只保留应用归属。
        if (pickupRun || names.any { it.contains("取件") || it.contains("取货") || it.contains("提货") || LearnGate.hasPickupCode(it) })
            return "${s.packageName ?: "未知应用"}（个人页面内容已隐藏）"
        return "${s.packageName ?: "unknown app"}: " + names.distinct().take(8).joinToString(", ")
    }

    private fun emit(e: RunEvent) {
        try { sink.event(e) } catch (x: CancellationException) { throw x } catch (x: Exception) { /* 记录失败不影响回放 */ }
    }

    companion object {
        // 必须是确认口径中的独立许可句；只提到“备用入口”或要求严格核实不能启用该分支。
        private val TEXT_ALTERNATIVE = Regex("""(?:^|[。；;\n])\s*(?:若|如果)?(?:看图|截图|视觉)?(?:找不到|无法核实)(?:图标|图形)?时?[，,]\s*(?:再|允许|可以)从(?:当前页(?:面)?(?:用|使用)?)?可核实的只读入口继续(?:[。；;\n]|$)""")
        /** 这些错误在动作执行之前就拒了,界面没被动过。 */
        private val NOT_EXECUTED = setOf(
            "invalid_arguments", "unknown_tool", "invalid_link", "link_unresolved", "ref_expired", "app_in_use_by_other_task",
            "app_wait_would_deadlock", "app_wait_timed_out",
            "app_in_use_by_user", "app_not_found", "no_app_open", "confirmation_required", "batch_stopped",
        )

        /** 屏宽 15%(0–999 坐标)。 */
        private const val NEAR = 150.0
        private const val ENTRY_WAIT_MS = 8_000L
        private const val ENTRY_POLL_MS = 250L
        private const val PROTECTED_SEEN = "a protected page"
        private val SLOT = Regex("\\{(\\d+)\\}")
        private val BACK_LABELS = setOf("返回", "返回按钮", "返回上一页", "back", "back button", "navigate up", "go back")
        private val SEARCH_ENTRANCES = setOf("搜索", "搜索栏", "搜索框", "search", "search bar", "search field")
        private val SEARCH_SUBMIT = Regex("""搜索(?:\s*按钮)?|search(?:\s+button)?""", RegexOption.IGNORE_CASE)
        private val CONFIRM_CONTROLS = setOf("确认", "确定", "取消", "放弃", "不保存", "confirm", "cancel", "discard", "don't save")

        /** 把 `{n}` 换成第 n 个槽值;槽号不存在为 null。 */
        internal fun fill(s: String, slots: List<String>): String? {
            var bad = false
            val out = SLOT.replace(s) { m -> slots.getOrNull(m.groupValues[1].toInt() - 1) ?: run { bad = true; "" } }
            return out.takeUnless { bad }
        }

        /** 给模型看的一步说明。 */
        fun describe(step: RouteStep, slots: List<String>): String {
            val o = try { Json.parseToJsonElement(step.argsJson) as? JsonObject } catch (e: Exception) { null }
            val what = listOfNotNull(
                step.target?.name?.let { fill(it, slots) }?.takeIf { it.isNotEmpty() }?.let { "「$it」" },
                (o?.get("text") as? JsonPrimitive)?.contentOrNull?.let { fill(it, slots) }?.let { "type 「$it」" },
                (o?.get("package") as? JsonPrimitive)?.contentOrNull,
                (o?.get("direction") as? JsonPrimitive)?.contentOrNull,
                (o?.get("key") as? JsonPrimitive)?.contentOrNull,
            ).joinToString(" ")
            return if (what.isEmpty()) step.tool else "${step.tool} $what"
        }
    }
}
