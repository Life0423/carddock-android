package com.life0423.carddock.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.life0423.carddock.MainActivity
import com.life0423.carddock.ui.theme.CardDockTheme
import com.life0423.carddock.ui.theme.DockColors
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.confirm.ConfirmationCopy
import com.wangzan101.mika.confirm.ConfirmationNotifier
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.RunProgress
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.remote.RemoteTask
import com.wangzan101.mika.core.remote.RemoteTaskService
import com.wangzan101.mika.core.remote.RunRecordExport
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunPhase
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.task.*
import com.wangzan101.mika.ui.RunTimelineViewModel
import com.wangzan101.mika.ui.TimelineUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 自由操作、卡片与草稿打开同一个执行过程，返回时保留上一页的编辑/滚动位置。 */
internal fun Context.openTaskDetail(taskId: String, backLabel: String = "返回自由操作", routeId: String? = null) {
    startActivity(Intent(this, TaskDetailActivity::class.java).putExtra("task", taskId)
        .putExtra("back", backLabel).putExtra("route", routeId))
}

/** 通知只读查询本次回执或运行编号；尚无任务的准备通知打开自由操作列表。 */
internal fun Context.taskNoticeIntentForDock(notice: TaskNotice): Intent =
    if (notice.taskId == null && notice.recordId == null && notice.runKey == null)
        Intent(this, MikaTasksActivity::class.java)
    else Intent(this, TaskDetailActivity::class.java).putExtra("task", notice.taskId)
        .putExtra("route", notice.routeId).putExtra("record", notice.recordId).putExtra("thread", notice.threadId).putExtra("run", notice.runKey)

/** 应用内的详情直接弹栈；通知进入时为系统返回与顶部返回还原同一个卡片、草稿或自由操作入口。 */
internal class TaskDetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = TaskNotice(intent.getStringExtra("thread"), intent.getStringExtra("run"),
            intent.getStringExtra("record"), intent.getStringExtra("task"), intent.getStringExtra("route"))
        val from = intent.getStringExtra("back")
        setContent {
            CardDockTheme { DockActionTheme {
                TaskDetail(target, from) { route ->
                    if (from == null) {
                        if (isTaskRoot) startActivity(Intent(this, MainActivity::class.java))
                        if (route != null) openRoutes(id = route.id) else openFreeTask()
                    }
                    finish()
                }
            } }
        }
    }
}

/** 三种可独立恢复的来源：本机提交回执、正在执行的服务任务、定时/中断任务已经落盘的结果。 */
private data class Detail(
    val title: String, val state: TaskState?, val recordId: String?, val live: TaskRun?, val receipt: RemoteTask?,
    val firstInTakeoverQueue: Boolean = true,
)

private fun MikaApp.loadDetail(target: TaskNotice): Detail? {
    val receipt = target.taskId?.let { remoteTasks.localTasks.get(it) }
    val live = taskState.runs.value.firstOrNull {
        (target.taskId != null && it.mcpExternalId == target.taskId) || (target.runKey != null && it.key == target.runKey)
    }
    val record = receipt?.runId ?: live?.runId?.value ?: target.recordId
    val goal = receipt?.goal ?: live?.goal
    if (goal != null) return Detail(goal, receipt?.state ?: live?.state?.value, record, live, receipt,
        firstInTakeoverQueue = live?.takeover?.firstInQueue != false)

    // 提醒、未启动及中断的轮次也有精确编号。旧通知绝不因同会话后来又跑过而改指另一轮。
    val turn = if (record != null) target.threadId?.let { conversations.turns(it).lastOrNull { turn -> turn.runId == record } } else null
    if (turn != null) {
        val state = when (turn.status) {
            "cancelled" -> TaskState.Cancelled
            "failed" -> TaskState.Failed(turn.outcome, FailKind.Environment)
            else -> TaskState.Succeeded(turn.outcome, verified = turn.verified == true, note = turn.note,
                unconfirmed = turn.unconfirmed, reviewed = turn.verified != null || turn.status == "unverified")
        }
        return Detail(turn.userText, state, record, null, null)
    }
    val saved = target.runKey?.let { runStore.get(RunId(it)) } ?: return null
    val reply = saved.reply
    val state = when {
        saved.runSucceeded == false -> TaskState.Failed(reply ?: "任务未完成。", FailKind.Environment)
        reply != null -> TaskState.Succeeded(reply, reviewed = false)
        saved.phase == RunPhase.Interrupted -> TaskState.Failed("运行已中断。", FailKind.Interrupted)
        else -> null
    }
    return Detail(saved.userText ?: saved.goal, state, record, null, null)
}

/** 更新模板会移除编辑草稿，旧通知仍可用原任务编号找到接收这次复验的模板。 */
private fun DockRoutes.findNoticeRoute(target: TaskNotice): DockRoute? {
    target.routeId?.let(::get)?.let { return it }
    val taskId = target.taskId ?: return null
    val rows = all()
    return rows.firstOrNull { it.taskId == taskId && !it.isTemplate }
        ?: rows.firstOrNull { it.taskId == taskId || it.trialTaskId == taskId }
}

@Composable
private fun TaskDetail(target: TaskNotice, from: String?, close: (DockRoute?) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MikaApp
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<Detail?>(null) }
    var route by remember { mutableStateOf<DockRoute?>(null) }
    var timeline by remember { mutableStateOf<TimelineUi>(TimelineUi.Loading) }
    var issue by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    val routes = remember { DockRoutes(context) }
    val back = { close(if (from == null && loading) routes.findNoticeRoute(target) else route) }
    BackHandler(onBack = back)

    LaunchedEffect(target, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                do {
                    route = withContext(Dispatchers.IO) { routes.findNoticeRoute(target) }
                    val next = withContext(Dispatchers.IO) { app.loadDetail(target) }
                    detail = next
                    loading = false
                    if (next == null || next.state is TaskState.Terminal || (next.live == null && next.receipt == null)) break
                    delay(900)
                } while (true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { loading = false; issue = "暂时无法读取这次任务。" }
        }
    }
    val current = detail
    val state = current?.state
    val live = current?.live
    val record = current?.recordId?.takeIf { RunRecordExport.RUN_ID.matches(it) }
    LaunchedEffect(record, state is TaskState.Terminal, lifecycle) {
        if (record == null) return@LaunchedEffect
        val vm = RunTimelineViewModel(RunStore(context), record, alive = { id -> app.taskState.runs.value.any { it.runId.value == id } })
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            do {
                val loaded = withContext(Dispatchers.IO) { vm.load() }
                timeline = loaded
                if ((loaded as? TimelineUi.Ready)?.header?.running != true) break
                delay(1000)
            } while (true)
        }
    }

    Surface(Modifier.fillMaxSize(), color = DockColors.Background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 18.dp)) {
            TextButton(back) { Text(from ?: route?.detailBackLabel ?: if (loading) "返回" else "返回自由操作") }
            HorizontalDivider(color = DockColors.Outline)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("执行过程", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
                if (current == null) item { Text(if (loading) "正在读取任务…" else "没有找到这次运行的记录。") }
                else {
                    item {
                        var showGoal by rememberSaveable { mutableStateOf(false) }
                        TextButton({ showGoal = !showGoal }) { Text(if (showGoal) "收起任务需求" else "查看任务需求") }
                        if (showGoal) Text(current.title, style = MaterialTheme.typography.titleMedium)
                    }
                    val currentRoute = route?.takeIf { it.busy && it.taskId == current.receipt?.id }
                    item { TaskStateCard(current, routes, currentRoute) { issue = it } }
                    val trace = timeline as? TimelineUi.Ready
                    if (trace != null) {
                        item { Text("关键步骤", style = MaterialTheme.typography.titleMedium) }
                        if (trace.steps.isEmpty()) item { Text("尚无步骤记录。") }
                        // 诊断只取时间线生成的动作、页面变化与核对标签；原屏文字、参数、截图可能含取件码。
                        items(trace.steps, key = { it.step }) { step ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("第 ${step.step} 步　" + (step.tool ?: "处理任务"), fontWeight = FontWeight.Medium)
                                step.pageChanges.forEach { (before, after) ->
                                    val where = remember(after) { ConfirmationNotifier.appLabel(context, after) }
                                    val previous = remember(before) { before?.let { ConfirmationNotifier.appLabel(context, it) } }
                                    Text(when (before) {
                                        null -> "进入 $where"
                                        after -> "$where · 页面内容有变化"
                                        else -> "$previous → $where"
                                    }, color = DockColors.Muted)
                                }
                                val notes = (listOfNotNull(step.result) + step.tags.map { it.text }).distinct()
                                if (notes.isNotEmpty()) Text(notes.joinToString(" · "), color = DockColors.Muted)
                                HorizontalDivider(color = DockColors.Outline)
                            }
                        }
                        item { Text("结果核对 · " + trace.header.verdict, color = DockColors.Muted) }
                    } else if (record != null && timeline == TimelineUi.Expired) item { Text("本次步骤记录已清理。") }
                    else if (record == null && state is TaskState.Terminal) item { Text("本次没有步骤记录。") }
                    val receipt = current.receipt
                    if (receipt != null && state is TaskState.Succeeded && route == null && (from == null || from == "返回自由操作")) item {
                        OutlinedButton({
                            saving = true
                            scope.launch {
                                try { context.openRoutes(id = withContext(Dispatchers.IO) { routes.fromFree(receipt) }) }
                                catch (e: CancellationException) { throw e }
                                catch (e: Exception) { issue = e.localizedMessage ?: "无法保存模板草稿。" }
                                finally { saving = false }
                            }
                        }, enabled = !saving, shape = DockButtonShape) { Text("保存为模板草稿") }
                    }
                    if (live != null && state !is TaskState.Terminal) item {
                        TextButton({
                            if (currentRoute == null) TaskService.cancel(context, live.key)
                            else scope.launch {
                                try { withContext(Dispatchers.IO) { routes.stop(currentRoute.id, expectedTaskId = currentRoute.taskId) } }
                                catch (e: CancellationException) { throw e }
                                catch (e: Exception) { issue = e.localizedMessage ?: "暂时无法停止这次任务。" }
                            }
                        }, enabled = currentRoute?.stopping != true) { Text(if (currentRoute?.stopping == true) "正在停止…" else "停止任务") }
                    }
                }
                if (issue.isNotBlank()) item { Text(issue, color = DockColors.Error) }
            }
        }
    }
}

/** 关卡只响应当前运行；确认和接管按钮都携带原申报编号，过期按钮由服务丢弃。 */
@Composable
private fun TaskStateCard(detail: Detail, routes: DockRoutes, route: DockRoute?, issue: (String) -> Unit) {
    val state = detail.state
    val live = detail.live
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ask = live?.activeAsk
    var answer by rememberSaveable(live?.key, ask?.askId) { mutableStateOf("") }
    var expanded by rememberSaveable(live?.key, ask?.askId, state?.javaClass?.name) { mutableStateOf(false) }
    val heading = when (state) {
        is TaskState.Succeeded -> "任务答复"
        is TaskState.Failed, TaskState.Cancelled -> "停止原因"
        is TaskState.WaitingInput -> "需要补充"
        is TaskState.WaitingTakeover -> "需要你接管"
        is TaskState.WaitingConfirmation -> ConfirmationCopy.title(state.request)
        is TaskState.Held -> "任务已暂停"
        else -> "任务进度"
    }
    val text = when (state) {
        null, TaskState.Idle -> "等待开始"
        is TaskState.Running -> state.narration ?: (state.progress as? RunProgress.Task)?.display ?: state.reason ?: state.note
        is TaskState.Succeeded -> state.answer
        is TaskState.Failed -> state.reason
        TaskState.Cancelled -> "任务已取消。"
        is TaskState.Held -> state.reason.why
        is TaskState.WaitingInput -> state.prompt
        is TaskState.WaitingTakeover -> listOfNotNull(state.message, state.item?.let { "项目 · $it" },
            state.amount?.let { "金额 · $it" }, state.address?.let { "地址 · $it" }).joinToString("\n")
        is TaskState.WaitingConfirmation -> ConfirmationCopy.detail(state.request, ConfirmationNotifier.appLabel(context, state.request.app))
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(heading, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            // 初次打开仅展示结构化状态；用户点查看后再呈现答复、现场提问、金额等业务内容。
            when (state) {
                null, TaskState.Idle, TaskState.Cancelled -> Text(text)
                is TaskState.Succeeded -> if (state.reviewed) Text(if (state.verified) "已核对" else "部分内容尚未核实")
                is TaskState.Running -> Text(if (state.step > 0) "正在执行第 ${state.step} 步" else "正在准备")
                is TaskState.Failed -> Text(when (state.kind) {
                    FailKind.Interrupted -> "运行中断"
                    FailKind.Environment -> "执行环境不可用"
                    FailKind.Guard -> "操作保护已停止任务"
                    FailKind.StepLimit -> "已达到步数上限"
                    FailKind.Model -> "模型调用未完成"
                    FailKind.GaveUp -> "当前条件下无法完成"
                })
                else -> Unit
            }
            if (state == null || state == TaskState.Idle || state == TaskState.Cancelled) return@Column
            val action = when (state) {
                is TaskState.Succeeded -> "查看任务答复"
                is TaskState.Failed -> "查看停止详情"
                is TaskState.WaitingInput -> "查看并回复"
                is TaskState.WaitingConfirmation -> "核对本次操作"
                is TaskState.WaitingTakeover -> "查看接管请求"
                is TaskState.Held -> "查看暂停原因"
                else -> "查看当前进度"
            }
            TextButton({ expanded = !expanded }) { Text(if (expanded) "收起详情" else action) }
            if (!expanded) return@Column
            Text(text)
            if (state is TaskState.Succeeded) {
                if (!state.verified && state.note != null) Text(state.note.orEmpty())
                if (state.unconfirmed.isNotEmpty()) Text(state.unconfirmed.joinToString("\n"))
            }
            if (live == null) return@Column
            when (state) {
                is TaskState.WaitingInput -> {
                    fun reply(value: String) {
                        val id = ask?.askId ?: return
                        scope.launch {
                            try {
                                if (route != null) withContext(Dispatchers.IO) { routes.answer(route.id, value, expectedTaskId = route.taskId, expectedAskId = id) }
                                else if (!live.answerFromChannel(value, id)) { issue("这次提问已结束。"); return@launch }
                                answer = ""
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { issue(e.localizedMessage ?: "暂时无法回复。") }
                        }
                    }
                    state.options.orEmpty().forEach { option -> OutlinedButton({ reply(option) }, enabled = ask != null) { Text(option) } }
                    OutlinedTextField(answer, { answer = it }, label = { Text("回复 Mika") }, modifier = Modifier.fillMaxWidth())
                    Button({ reply(answer.trim()) }, enabled = ask != null && answer.trim().length in 1..RemoteTaskService.MAX_ANSWER) { Text("发送回复") }
                }
                is TaskState.WaitingConfirmation -> {
                    val key = ConfirmationNotifier.requestKey(state)
                    Text(ConfirmationCopy.NOTE)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ TaskService.decline(context, key, live.key) }) { Text("拒绝") }
                        Button({ TaskService.approve(context, key, live.key) }) { Text("仅允许这一次") }
                    }
                }
                is TaskState.Held -> if (state.reason.resumable) Button({ TaskService.resume(context, live.key) }) { Text("继续任务") }
                is TaskState.WaitingTakeover -> live.takeover?.let { takeover ->
                    val stage by takeover.stage.collectAsState()
                    val problem by takeover.problem.collectAsState()
                    val key = takeover.waitKey
                    if (problem != null) Text(when (problem) {
                        Takeover.Problem.MoveBackFailed -> "未能交回，请再试一次。"
                        Takeover.Problem.WebBusy -> "网页正在被使用，请稍后再试。"
                        else -> "未能打开接管页面，请再试一次。"
                    })
                    when (stage) {
                        Takeover.Stage.Waiting -> if (detail.firstInTakeoverQueue) {
                            Button({ TaskService.takeOver(context, key, live.key) }) { Text("我来处理") }
                            OutlinedButton({ TaskService.skip(context, key, live.key) }) { Text("跳过这一步") }
                        } else Text("另一项任务正在接管，交回后可继续。")
                        Takeover.Stage.HandedOver -> {
                            Button({ TaskService.handBack(context, key, live.key) }) { Text("交回并继续") }
                            OutlinedButton({ TaskService.finishedMyself(context, key, live.key) }) { Text("我已自行完成") }
                        }
                        Takeover.Stage.None -> Unit
                    }
                }
                else -> Unit
            }
        }
    }
}
