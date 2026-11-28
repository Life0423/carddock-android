package com.wangzan101.mika.task

import com.wangzan101.mika.core.agent.DockCardIntent
import com.wangzan101.mika.core.verify.CardPrice
import android.content.Context
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.agent.LearnGate
import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.remote.RemoteTask
import com.wangzan101.mika.core.remote.RemoteTaskError
import com.wangzan101.mika.core.remote.RemoteTaskService
import com.wangzan101.mika.core.replay.Route
import com.wangzan101.mika.core.replay.RouteRecorder
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.core.assembly.allowsPhoneBrowse
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.uistate.IdentityCodeScreen
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/** 每条记录持有独立路线快照；经验库的替换和淘汰不会改变用户保存的卡片。 */
@Serializable
data class DockRoute(
    val id: String, val goal: String, val taskId: String, val phase: Phase = Phase.Trial,
    val route: Route? = null, val slots: List<String> = emptyList(), val saved: Boolean = false,
    val templateId: String? = null, val isTemplate: Boolean = false,
    val scope: String = "", val sampleAt: Long = 0, val sourcePackage: String? = null,
    val isolated: Boolean = false, val explorationContext: String = "",
    val sampleVerified: Boolean = false,
    val sample: String = "", val result: String = "", val resultAt: Long = 0,
    val clarifiedGoal: String? = null, val resultGoal: String = "", val issue: String = "", val askEachTime: Boolean = false,
    val stopping: Boolean = false, val deleteAfterStop: Boolean = false, val lastAnswer: String = "",
    val trialTaskId: String? = null, val originalGoal: String? = null,
    val cardIntent: DockCardIntent? = null, val sourceChoices: List<String> = emptyList(),
    val price: CardPrice? = null, val previousPrice: CardPrice? = null,
    val priceChanges: List<PriceChange> = emptyList(),
    val daily: Boolean = false, val notifyDrop: Boolean = false, val scheduledRun: Boolean = false,
    val identityPageOnlySample: Boolean = false,
    val openPageOnRun: Boolean = LearnGate.identityCodeQuery(goal),
) {
    @Serializable data class PriceChange(val price: CardPrice, val at: Long)
    enum class Phase { Draft, Trial, Scope, Checking, Ready, Running, Failed, Stopped }
    val busy get() = phase in setOf(Phase.Trial, Phase.Checking, Phase.Running)
    val isCard get() = saved && !isTemplate
    fun demand(values: List<String> = slots) = route?.let {
        Regex("\\{(\\d+)\\}").replace(it.template) { match -> values[match.groupValues[1].toInt() - 1] }
    } ?: goal
}

/** 任务回执和卡片按本地 UUID 关联。每次点击创建新回执，试查和验证使用不同虚拟屏任务。 */
class DockRoutes(context: Context) {
    private val app = context.applicationContext
    val prefs = app.getSharedPreferences("mika_route_cards_v1", Context.MODE_PRIVATE)
    // 旧版模板与卡片共用一行；一次提交保留原卡 ID，并拆出独立模板。
    init {
        synchronized(lock) {
            // 旧自由模板只有需求文字；用稳定 ID 导入草稿，落盘成功后再清理旧集合。
            val old = app.getSharedPreferences("mika_free_templates", Context.MODE_PRIVATE)
            val goals = old.getStringSet("goals", emptySet()).orEmpty()
            if (goals.isNotEmpty()) {
                val drafts = prefs.edit()
                for (goal in goals) {
                    val id = "goal:" + UUID.nameUUIDFromBytes(goal.encodeToByteArray())
                    if (!prefs.contains(id)) drafts.putString(id, Json.encodeToString(DockRoute.serializer(),
                        DockRoute(id, goal, "", DockRoute.Phase.Draft)))
                }
                if (drafts.commit()) old.edit().remove("goals").apply()
            }
            val legacy = all().filter { it.saved && !it.isTemplate && it.templateId == null }
            if (legacy.isNotEmpty()) prefs.edit().also { edit ->
                for (card in legacy) {
                    val tid = "template:" + card.id
                    val template = templateSnapshot(card, tid)
                    edit.putString(tid, Json.encodeToString(DockRoute.serializer(), template))
                    edit.putString(card.id, Json.encodeToString(DockRoute.serializer(), card.copy(templateId = tid)))
                }
            }.apply()
        }
    }
    private val tasks get() = RemoteTaskService((app as MikaApp).remoteTasks.localTasks)
    fun all() = prefs.all.values.filterIsInstance<String>().map { Json.decodeFromString(DockRoute.serializer(), it) }
    fun forTask(taskId: String?) = taskId?.let { id -> all().find { it.taskId == id && it.busy } }
    fun get(id: String) = prefs.getString(id, null)?.let { Json.decodeFromString(DockRoute.serializer(), it) }
    private fun put(row: DockRoute) {
        prefs.edit().putString(row.id, Json.encodeToString(DockRoute.serializer(), row)).apply()
    }
    fun trial(goal: String, isolated: Boolean = false, templateId: String? = null): String {
        val id = UUID.randomUUID().toString()
        beginTrial(DockRoute(id, goal.trim(), id, isolated = isolated, templateId = templateId))
        return id
    }
    /** 宿主处理触发方式及展示位置；多个取件来源可选时，先在草稿里选定。 */
    private fun beginTrial(row: DockRoute) {
        val intent = DockCardIntent.interpret(row.goal)
        val apps = com.wangzan101.mika.cap.phone.a11y.launchableApps(app.packageManager, app.packageName)
        val couriers = apps.filter { it.first in setOf("com.cainiao.wireless", "com.taobao.taobao") }
        val labels = apps + couriers.map { it.first to if (it.first == "com.taobao.taobao") "淘宝" else "菜鸟" }
        val explicit = labels.filter { it.second.isNotBlank() && it.second in row.goal }
            .minByOrNull { row.goal.indexOf(it.second) }
        val rowWithIntent = row.copy(cardIntent = intent, openPageOnRun = intent.opensPage,
            daily = intent == DockCardIntent.DailyPrice, price = null, previousPrice = null, sourceChoices = emptyList(),
            sourcePackage = explicit?.first ?: if (intent == DockCardIntent.PickupPage) couriers.singleOrNull()?.first else null)
        if (intent == DockCardIntent.PickupPage && rowWithIntent.sourcePackage == null && couriers.size > 1)
            put(rowWithIntent.copy(phase = DockRoute.Phase.Draft, sourceChoices = couriers.map { it.first }, issue = "从哪个应用打开官方取件页？"))
        else launch(rowWithIntent)
    }
    fun chooseSource(id: String, pkg: String) = synchronized(lock) {
        val draft = requireNotNull(get(id))
        require(pkg in draft.sourceChoices)
        launch(draft.copy(phase = DockRoute.Phase.Trial, sourcePackage = pkg, sourceChoices = emptyList(), issue = ""))
    }
    fun track(id: String, daily: Boolean, notify: Boolean) = synchronized(lock) {
        val row = requireNotNull(get(id))
        require(row.cardIntent?.price == true)
        put(row.copy(daily = daily, notifyDrop = notify))
    }
    /** 用户在试查中选过商品或补充门店，下一轮从普通入口验证时带上这项选择。 */
    suspend fun answer(id: String, answer: String, expectedTaskId: String, expectedAskId: String) {
        val row = requireNotNull(get(id))
        check(row.taskId == expectedTaskId && row.busy) { "此运行已结束。" }
        val value = answer.trim()
        require(value.length in 1..RemoteTaskService.MAX_ANSWER) { "回复应为 1 至 2000 字。" }
        val clarified = (row.clarifiedGoal ?: row.goal) + "\n补充：" + value
        if (row.phase == DockRoute.Phase.Trial) require(clarified.length <= RemoteTaskService.MAX_GOAL) { "补充后的需求超过 4000 字。" }
        val run = (app as MikaApp).taskState.runs.value.firstOrNull { it.mcpExternalId == expectedTaskId }
        check(run?.answerFromChannel(value, expectedAskId) == true) { "这次提问已结束。" }
        synchronized(lock) {
            val current = get(id)
            if (current?.taskId == expectedTaskId && current.phase == DockRoute.Phase.Trial)
                put(current.copy(clarifiedGoal = clarified))
        }
    }
    /** 复验中断时重新从入口验证同一条已试查路线。独立核对成功后才能保存。 */
    fun recheck(id: String) = synchronized(lock) {
        val row = requireNotNull(get(id))
        if (row.phase != DockRoute.Phase.Failed || row.saved || row.route == null) return@synchronized
        // 老候选可从其原始试查回执补齐页面标识，路线步骤须与原记录逐项一致。
        val source = (app as MikaApp).remoteTasks.localTasks.get(row.trialTaskId ?: row.id.removePrefix("free:"))?.runId
        val route = if (row.route.terminalLabels.isEmpty() && source != null) {
            val store = com.wangzan101.mika.record.RunStore(app)
            if (store.summary(source)?.goal == row.goal) com.wangzan101.mika.core.replay.RouteRecorder.withReadCheckpoints(row.route, store.events(source), row.slots)
            else row.route
        } else row.route
        if (row.scope.isBlank()) put(row.copy(route = route, phase = DockRoute.Phase.Scope, issue = ""))
        else launch(row.copy(taskId = UUID.randomUUID().toString(), route = route, phase = DockRoute.Phase.Checking, issue = ""))
    }
    /** 同一需求试查已取得结果，但所录路径偏离时，可重验用户已保存过的另一条路线。 */
    fun alternativeFor(row: DockRoute, records: List<DockRoute> = all()): DockRoute? {
        if (row.phase != DockRoute.Phase.Failed || row.saved || row.scope.isBlank()) return null
        val pkg = row.route?.packages?.singleOrNull() ?: return null
        return records.filter { other ->
            other.saved && other.isTemplate && other.phase == DockRoute.Phase.Ready &&
                other.scope == row.scope && other.route?.packages == listOf(pkg) &&
                other.demand() == row.demand() && (other.route.steps != row.route.steps || other.slots != row.slots ||
                    other.route.entryButtonRow != row.route.entryButtonRow || other.route.terminalLabels != row.route.terminalLabels)
        }.maxByOrNull { it.resultAt }
    }

    fun recheckWithAlternative(id: String) = synchronized(lock) {
        val row = requireNotNull(get(id))
        val other = alternativeFor(row) ?: error("已找不到同需求、同口径的备用模板。")
        // 只取步骤与参数，保留本次试查的样例；新任务仍需从应用入口独立复验。
        launch(row.copy(taskId = UUID.randomUUID().toString(), route = other.route, slots = other.slots,
            phase = DockRoute.Phase.Checking, issue = ""))
    }
    /** 在独立复验任务内使用用户接受的规则，具体价格和取值从新页面读取。 */
    fun confirmScope(id: String, scope: String) = synchronized(lock) {
        val row = requireNotNull(get(id))
        check(row.phase == DockRoute.Phase.Scope && row.route != null)
        require(scope.isNotBlank() && scope.length <= 800)
        require(!row.identityPageOnlySample || row.cardIntent == DockCardIntent.IdentityPage || LearnGate.identityArrivalOnly(scope)) { "试查只核实了页面到达。请选择只验证打开身份码页，或继续查明展示。" }
        require(!LearnGate.hasPickupCode(scope)) { "查询口径中包含取件码，请移除具体码值。" }
        launch(row.copy(taskId = UUID.randomUUID().toString(), phase = DockRoute.Phase.Checking, scope = scope.trim(), issue = ""))
    }
    fun continueExploration(id: String, direction: String) = synchronized(lock) {
        val row = requireNotNull(get(id))
        check(!row.busy && !row.saved)
        val goal = if (direction.isBlank()) row.goal else row.goal + "\n补充：" + direction.trim()
        restartTrial(id, goal, row.sample)
    }
    /** 在同一份草稿上重试，保留旧答复作线索，新的回执单独记录。 */
    fun restartTrial(id: String, goal: String, previousSample: String = "") = synchronized(lock) {
        val row = requireNotNull(get(id))
        check(!row.busy && !row.saved)
        require(goal.trim().length in 1..RemoteTaskService.MAX_GOAL) { "需求应为 1 至 4000 字。" }
        beginTrial(row.copy(taskId = UUID.randomUUID().toString(), route = null, slots = emptyList(), phase = DockRoute.Phase.Trial,
            explorationContext = previousSample.take(1400), goal = goal.trim(), originalGoal = row.originalGoal ?: row.goal,
            clarifiedGoal = null, scope = "", issue = "", identityPageOnlySample = false))
    }
    /** 原回执省略来源正文；用同一 run 的来源事件还原核对证据。 */
    private fun receiptPrice(events: List<RunEvent>) = events.filterIsInstance<RunEvent.State>()
        .lastOrNull { it.name == "dock_price" }?.let { Json.decodeFromString(CardPrice.serializer(), it.detail) }

    private fun withSources(end: TaskState.Succeeded, events: List<RunEvent>) =
        if (end.sources.isNotEmpty()) end else end.copy(sources = events.filterIsInstance<RunEvent.Sources>().lastOrNull()?.items.orEmpty())

    /** 自由任务还须排除写操作和其他工具；只导入原记录，不在导入时操作手机。 */
    private fun fromReceipt(row: DockRoute, task: RemoteTask, free: Boolean): DockRoute {
        val end = task.state as? TaskState.Succeeded ?: error("该任务没有成功答复。")
        val events = task.runId?.let { RunStore(app).events(it) }.orEmpty()
        val tools = com.wangzan101.mika.core.assembly.recordedTools()
        val unsafe = free && events.any { event -> when (event) {
            is RunEvent.Activity -> event.outward
            is RunEvent.Approval -> event.payment || event.decision != "denied" || events.any {
                it is RunEvent.ActionTarget && it.step == event.step && it.tool == event.tool && it.effective
            }
            is RunEvent.Confirmation -> true
            is RunEvent.ToolResult -> event.tool != "do_steps" && !tools.allowsPhoneBrowse(event.tool)
            is RunEvent.ModelReply -> event.acted && event.tool != null && event.tool != "do_steps" && !tools.allowsPhoneBrowse(requireNotNull(event.tool))
            else -> false
        } }
        val restored = withSources(end, events)
        val verified = LearnGate.verifiedRead(restored)
        val goal = row.clarifiedGoal ?: row.goal
        val lastApp = events.filterIsInstance<RunEvent.ActionTarget>().lastOrNull { it.effective }?.after?.packageName
        val identity = LearnGate.identityCodeQuery(goal)
        val apps by lazy { com.wangzan101.mika.cap.phone.a11y.launchableApps(app.packageManager, app.packageName) }
        val displayed = if (identity) LearnGate.identitySource(goal, events, apps) else null
        val identitySource = if (identity) displayed ?: LearnGate.identitySource(goal, events, apps, allowArrival = true) else null
        val emptyClaim = !identity && LearnGate.claimsEmptyPickup(goal, end.answer)
        val emptyProven = emptyClaim && LearnGate.verifiedEmptyPickup(goal, restored, events, lastApp)
        val pickupNeedsProof = !identity && (emptyClaim || LearnGate.pickupCodeQuery(goal))
        val pickupProven = emptyProven || !emptyClaim && pickupNeedsProof && LearnGate.verifiedPickupCodes(restored, events, lastApp)
        val verdict = RunOutcome.recorded(events)
        val pickupSource = events.filterIsInstance<RunEvent.State>().lastOrNull { it.name == IdentityCodeScreen.PICKUP_EVENT }?.detail
        val quote = receiptPrice(events)
        val acceptsSample = when {
            row.cardIntent == DockCardIntent.PickupPage -> pickupSource != null && pickupSource == (row.sourcePackage ?: pickupSource)
            row.cardIntent?.price == true -> quote != null
            identity -> identitySource != null
            pickupNeedsProof -> pickupProven
            else -> free || verified && verdict != RunOutcome.NotPossible
        }
        val recording = if (!unsafe && acceptsSample)
            RouteRecorder.recordBrowse(goal, events, events.lastOrNull()?.atMs ?: task.createdAtMs, tools, quote?.key) else null
        val route = recording?.route
        val issue = when {
            route != null -> ""
            events.isEmpty() -> "上次运行已中断，未找到执行记录。"
            unsafe -> "这次记录包含浏览以外的操作，请重新试查。"
            identity && identitySource == null -> "缺少本次指定应用的身份码末屏证明。"
            pickupNeedsProof && !pickupProven -> if (emptyClaim) "缺少实际待取件页的空态证据。" else "本次记录无法核实取件码，可按原需求重新试查。"
            else -> recording?.reason ?: "这次答复缺少页面核对证据。"
        }
        return row.copy(price = quote, goal = goal, clarifiedGoal = null, trialTaskId = task.id, stopping = false, deleteAfterStop = false,
            phase = if (route == null) DockRoute.Phase.Failed else DockRoute.Phase.Scope,
            sample = when { displayed != null -> "已核实身份码页的出示或动态刷新提示。"
                identitySource != null -> IdentityCodeScreen.ARRIVED_ANSWER
                identity -> ""
                else -> end.answer },
            identityPageOnlySample = identitySource != null && displayed == null,
            sampleVerified = if (row.cardIntent == DockCardIntent.PickupPage || row.cardIntent?.price == true) acceptsSample else if (identity) displayed != null || identitySource != null && (row.cardIntent == DockCardIntent.IdentityPage || LearnGate.identityArrivalOnly(goal)) else verified && (!pickupNeedsProof ||
                pickupProven && LearnGate.matchesPickupSource(goal, lastApp, apps)),
            sampleAt = events.lastOrNull()?.atMs ?: task.createdAtMs,
            sourcePackage = if (identity) identitySource else pickupSource ?: quote?.key?.pkg ?: lastApp ?: restored.sources.firstOrNull()?.place,
            route = route, slots = recording?.slots.orEmpty(), issue = issue)
    }

    /** 再次导入时允许升级原来提取失败的候选；已编辑、验证或保存的版本继续沿用。 */
    fun fromFree(task: RemoteTask): String = synchronized(lock) {
        val id = "free:" + task.id
        val prior = get(id)
        if (prior != null && (prior.saved || prior.phase != DockRoute.Phase.Failed || prior.route != null || prior.taskId != task.id))
            return@synchronized id
        val candidate = fromReceipt(prior ?: DockRoute(id, task.goal, task.id), task, free = true)
        commitCandidate(candidate, prior)
        id
    }

    /** 详情或验收代码收到候选时，路线和阶段已写入磁盘；下一进程可以直接确认口径。 */
    private fun commitCandidate(candidate: DockRoute, prior: DockRoute?) {
        val key = candidate.id
        if (!prefs.edit().putString(key, Json.encodeToString(DockRoute.serializer(), candidate)).commit()) {
            prefs.edit().putString(key, prior?.let { Json.encodeToString(DockRoute.serializer(), it) }).apply()
            error("候选模板未能写入手机存储，请从原任务重试。")
        }
    }

    /** 复验失败的旧候选仍从原始试查提取；提取成功后交还用户重新确认口径。 */
    fun recoverCandidate(id: String) = synchronized(lock) {
        val row = requireNotNull(get(id))
        check(!row.saved && row.phase in setOf(DockRoute.Phase.Failed, DockRoute.Phase.Scope))
        val original = row.trialTaskId ?: if (row.route == null) row.taskId else id.removePrefix("free:")
        val task = tasks.get(original)
        val recovered = fromReceipt(row.copy(taskId = task.id), task, free = row.id == "free:" + task.id)
        if (row.route != null && recovered.route == null) error(recovered.issue)
        commitCandidate(recovered, row)
    }

    /** 保存模板后按需实例化；每张卡只有自己的回执和结果。 */
    fun createAndRun(id: String, values: List<String>): String = synchronized(lock) {
        val template = requireNotNull(get(id))
        check(template.saved && template.isTemplate && template.route != null)
        require(values.size == template.slots.size && values.none(String::isBlank))
        val card = UUID.randomUUID().toString()
        launch(template.copy(priceChanges = emptyList(), previousPrice = null, id = card, taskId = card, templateId = id, isTemplate = false, slots = values,
            phase = DockRoute.Phase.Running, result = "", resultAt = 0, resultGoal = "", sample = "", issue = ""))
        card
    }
    fun adopt(id: String) = synchronized(lock) {
        val card = requireNotNull(get(id))
        val template = requireNotNull(card.templateId?.let(::get))
        check(card.saved && !card.busy && template.isTemplate && template.route != null)
        put(card.copy(cardIntent = template.cardIntent, sourcePackage = template.sourcePackage, openPageOnRun = template.openPageOnRun,
            daily = template.daily, notifyDrop = template.notifyDrop, price = template.price, previousPrice = null,
            result = "", resultAt = 0, priceChanges = emptyList(), route = template.route, scope = template.scope, slots = template.slots, goal = template.goal,
            phase = DockRoute.Phase.Ready, issue = "", resultGoal = "上次路线 · " + card.resultGoal))
    }
    /** 取件任务可以输出无标签码值；保存和旧卡迁移都只保留路线与口径。 */
    private fun templateSnapshot(row: DockRoute, target: String): DockRoute {
        val privateResult = listOf(row.goal, row.scope, row.demand()).any {
            LearnGate.privateCodeQuery(it)
        } || listOf(row.sample, row.result).any(LearnGate::hasPickupCode)
        return row.copy(id = target, saved = true, isTemplate = true, templateId = null, phase = DockRoute.Phase.Ready,
            sample = if (privateResult) "" else row.sample, result = if (privateResult) "" else row.result,
            resultGoal = if (privateResult) "" else row.resultGoal, explorationContext = "", lastAnswer = "",
            originalGoal = null, clarifiedGoal = null, stopping = false, deleteAfterStop = false, issue = "", identityPageOnlySample = false)
    }

    fun save(id: String): String = synchronized(lock) {
        val row = requireNotNull(get(id))
        check(row.phase == DockRoute.Phase.Ready && row.route != null)
        val target = row.templateId?.takeIf { get(it)?.isTemplate == true } ?: id
        require(!LearnGate.hasPickupCode(row.scope)) { "查询口径中包含取件码，请修改口径后再验证。" }
        val template = templateSnapshot(row, target)
        val previous = prefs.getString(target, null)
        val written = prefs.edit().also {
            if (id != target) it.remove(id)
            it.putString(target, Json.encodeToString(DockRoute.serializer(), template))
        }.commit()
        if (!written) {
            prefs.edit().putString(target, previous).putString(id, Json.encodeToString(DockRoute.serializer(), row)).apply()
            error("模板未能写入手机存储，请重试保存。")
        }
        target
    }
    fun remove(id: String) = synchronized(lock) {
        if (get(id)?.busy == false) prefs.edit().remove(id).apply()
    }
    fun configure(id: String, values: List<String>, ask: Boolean) = synchronized(lock) {
        val row = requireNotNull(get(id))
        require(row.saved && !row.busy && values.size == row.slots.size && values.none(String::isBlank))
        put(row.copy(slots = values, askEachTime = ask))
    }
    fun setOpenPageOnRun(id: String, enabled: Boolean) = synchronized(lock) {
        val card = requireNotNull(get(id))
        check(card.isCard && !card.busy)
        put(card.copy(openPageOnRun = enabled))
    }
    fun execute(id: String, values: List<String>? = null, scheduled: Boolean = false) = synchronized(lock) {
        val row = requireNotNull(get(id))
        if (row.busy || scheduled && !row.daily) return@synchronized
        check(row.saved && !row.isTemplate && row.route != null)
        launch(row.copy(taskId = UUID.randomUUID().toString(), phase = DockRoute.Phase.Running,
            slots = values ?: row.slots, scheduledRun = scheduled, issue = ""))
    }
    /** 先持久化对应关系，再进普通 Mika 本机任务入口，服务启动时就能取到固定路线。 */
    private fun launch(row: DockRoute) = synchronized(lock) {
        put(row.copy(stopping = false, deleteAfterStop = false, lastAnswer = ""))
        try { tasks.start(row.demand(), requestId = row.taskId) }
        catch (e: Exception) { fail(row.taskId, e.localizedMessage ?: "无法启动 Mika") }
    }
    /** 对可见列表或进程启动时的待办回执逐条核对；暂时读不到的留给下次刷新。 */
    fun reconcileBusy(taskIds: List<String> = all().filter { it.busy }.map { it.taskId }): Boolean {
        for (taskId in taskIds) {
            try { reconcile(taskId) }
            catch (e: RemoteTaskError) { android.util.Log.w("DockRoutes", "任务回执暂不可用", e) }
            catch (e: android.database.sqlite.SQLiteException) { android.util.Log.w("DockRoutes", "任务收件库暂不可用", e) }
        }
        return all().any { it.taskId in taskIds && it.busy }
    }

    /** 查看空间、模板或详情时，用后台终态补齐旧版遗留记录；活任务仍由服务收尾。 */
    fun reconcile(taskId: String): RemoteTask? = synchronized(lock) {
        val row = forTask(taskId) ?: return@synchronized null
        val task = try { tasks.get(taskId) } catch (e: RemoteTaskError) {
            if (e.code !in setOf(RemoteTaskError.Code.NOT_FOUND, RemoteTaskError.Code.GONE)) throw e
            fail(taskId, "上次运行已中断，未找到任务回执。")
            return@synchronized null
        }
        // 尚由服务持有的成功任务可能仍在写判定事件。由服务完成这一轮交接。
        if (task.state is TaskState.Succeeded && (app as MikaApp).taskState.runs.value.any { it.mcpExternalId == taskId }) return@synchronized task
        when (val end = task.state) {
            TaskState.Cancelled -> fail(taskId, "任务已停止。", cancelled = true)
            is TaskState.Failed -> fail(taskId, if (end.kind == com.wangzan101.mika.core.agent.FailKind.Interrupted) "上次运行已中断。" else end.reason)
            is TaskState.Succeeded -> {
                if (row.deleteAfterStop) prefs.edit().remove(row.id).apply()
                else if (row.phase == DockRoute.Phase.Trial) put(fromReceipt(row, task, free = false))
                else {
                    val events = task.runId?.let { RunStore(app).events(it) }.orEmpty()
                    val restored = withSources(end, events)
                    val outcome = RunOutcome.recorded(events) ?: RunOutcome.Unknown
                    val replay = events.filterIsInstance<RunEvent.Replay>()
                    val count = row.route?.steps?.size ?: 0
                    val fullReplay = count > 0 && replay.map { it.step } == (1..count).toList() &&
                        replay.all { it.routeId == row.route?.id && it.matched && it.total == count }
                    val requested = row.scope.ifBlank { row.clarifiedGoal ?: row.demand() }
                    val pickupGoal = row.goal + "\n" + requested
                    val identity = LearnGate.identityCodeQuery(pickupGoal)
                    val pickup = LearnGate.claimsEmptyPickup(pickupGoal, restored.answer) || LearnGate.pickupCodeQuery(pickupGoal)
                    val apps by lazy { com.wangzan101.mika.cap.phone.a11y.launchableApps(app.packageManager, app.packageName) }
                    val displayed = if (identity) LearnGate.identitySource(pickupGoal, events, apps) else null
                    val identitySource = if (identity) displayed ?: LearnGate.identitySource(pickupGoal, events, apps, allowArrival = true) else null
                    val pickupSource = events.filterIsInstance<RunEvent.State>().lastOrNull { it.name == IdentityCodeScreen.PICKUP_EVENT }?.detail
                    val quote = receiptPrice(events)
                    val pageDelivery = row.cardIntent == DockCardIntent.PickupPage
                    val proven = if (pageDelivery) pickupSource != null && pickupSource == (row.sourcePackage ?: pickupSource)
                        else if (row.cardIntent?.price == true) quote != null && quote.key == row.price?.key
                        else if (identity) displayed != null || identitySource != null && (row.cardIntent == DockCardIntent.IdentityPage || LearnGate.identityArrivalOnly(requested)) else !pickup ||
                        LearnGate.verifiedPickupResult(pickupGoal, requested, restored, events, apps)
                    val done = fullReplay && proven && (identity || pageDelivery || quote != null || LearnGate.verifiedRead(restored)) && LearnGate.done(restored, outcome)
                    val route = if (done && row.phase == DockRoute.Phase.Checking)
                        row.route?.let { RouteRecorder.withReadCheckpoints(it, events, row.slots) } else null
                    val answer = when { displayed != null -> "已核实身份码页的出示或动态刷新提示。"
                        identitySource != null -> IdentityCodeScreen.ARRIVED_ANSWER
                        identity -> ""
                        else -> end.answer }
                    settled(taskId, answer, done, route, row.slots, when {
                        events.isEmpty() -> "上次运行已中断，未找到执行记录。"
                        identity && !proven -> if (identitySource != null) IdentityCodeScreen.ARRIVED_ANSWER else "缺少本次指定应用的身份码末屏证明。"
                        !proven -> "已取回本次答复，原记录无法核实指定来源的待取空态或实际码值。"
                        else -> "已取回本次答复，缺少独立路线核对记录。"
                    }, sourcePackage = identitySource ?: pickupSource ?: quote?.key?.pkg, quote = quote,
                        at = events.filterIsInstance<RunEvent.TaskEnded>().lastOrNull()?.atMs ?: task.createdAtMs)
                }
            }
            else -> Unit
        }
        task
    }

    /** 先取得取消回执，再显示停止中；回执已结束时保留真正的结果。 */
    fun stop(id: String, delete: Boolean = false, expectedTaskId: String? = null, scheduledIssue: String? = null) = synchronized(lock) {
        val row = get(id) ?: return@synchronized
        if (scheduledIssue != null && (!row.scheduledRun || expectedTaskId != row.taskId)) return@synchronized
        check(expectedTaskId == null || expectedTaskId == row.taskId) { "此运行已结束。" }
        if (!row.busy) { if (delete) remove(id); return@synchronized }
        if (row.stopping) {
            if (delete) put(row.copy(deleteAfterStop = true))
            reconcile(row.taskId)
            return@synchronized
        }
        reconcile(row.taskId)
        if (get(id)?.busy != true) { if (delete) remove(id); return@synchronized }
        val cancellation = tasks.cancel(row.taskId)
        if (cancellation.accepted) put(row.copy(stopping = true, deleteAfterStop = delete, issue = scheduledIssue ?: row.issue))
        reconcile(row.taskId)
        if (!cancellation.accepted && get(id)?.busy == true) error("暂未收到停止确认，请重试。")
        if (delete && get(id)?.busy == false) remove(id)
    }

    fun fail(taskId: String, reason: String, cancelled: Boolean = false) = synchronized(lock) {
        val row = forTask(taskId) ?: return@synchronized
        if (row.deleteAfterStop) prefs.edit().remove(row.id).apply()
        else {
            val interrupted = row.scheduledRun && row.issue.startsWith("每日核价待补查")
            put(row.copy(phase = if (cancelled && !interrupted) DockRoute.Phase.Stopped else DockRoute.Phase.Failed,
                stopping = false, issue = if (interrupted) row.issue else reason))
        }
    }
    /** 成功答复须通过结局核对；首次仅产生候选，另起任务走完指定路线后才开放保存。 */
    fun settled(taskId: String, answer: String, done: Boolean, candidate: Route?, values: List<String>, reason: String, sourcePackage: String? = null, sampleVerified: Boolean = done, at: Long = System.currentTimeMillis(), identityPageOnlySample: Boolean = false, quote: CardPrice? = null) = synchronized(lock) {
        val row = forTask(taskId)?.takeIf { it.busy } ?: return@synchronized
        if (row.deleteAfterStop) { prefs.edit().remove(row.id).apply(); return@synchronized }
        if (row.cardIntent?.price == true && done && (quote == null || row.phase != DockRoute.Phase.Trial && row.price?.key?.let { it != quote.key } == true)) {
            put(row.copy(stopping = false, phase = DockRoute.Phase.Failed, lastAnswer = "", issue = "未核对同一商品、已选规格和价格标签。"))
            return@synchronized
        }
        if (row.phase == DockRoute.Phase.Trial) {
            put(row.copy(price = quote, stopping = false, trialTaskId = taskId, phase = if (candidate != null) DockRoute.Phase.Scope else DockRoute.Phase.Failed,
                goal = row.clarifiedGoal ?: row.goal, clarifiedGoal = null, route = candidate, slots = values,
                sample = answer, sampleVerified = sampleVerified, sourcePackage = sourcePackage, identityPageOnlySample = identityPageOnlySample,
                sampleAt = at, issue = if (candidate != null) "" else reason.ifBlank { "本次答复已保留，暂未取得可复现的路线。" }))
        } else if (!done) {
            put(row.copy(stopping = false, phase = DockRoute.Phase.Failed, lastAnswer = answer, issue = reason.ifBlank { "本次结果未通过核对。" }))
        } else {
            val changes = if (row.isCard && quote != null && row.priceChanges.lastOrNull()?.price != quote)
                (row.priceChanges + DockRoute.PriceChange(quote, at)).takeLast(60) else row.priceChanges
            put(row.copy(priceChanges = changes, price = quote ?: row.price, previousPrice = if (quote != null && row.isCard && row.resultAt > 0) row.price else row.previousPrice, stopping = false, phase = DockRoute.Phase.Ready, route = candidate ?: row.route, result = answer,
                sourcePackage = sourcePackage ?: row.sourcePackage, resultGoal = row.demand(), resultAt = at, issue = ""))
        }
    }

    companion object { private val lock = Any() }
}
