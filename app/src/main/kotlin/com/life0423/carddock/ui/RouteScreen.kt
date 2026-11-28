package com.life0423.carddock.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.wangzan101.mika.core.agent.LearnGate
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.life0423.carddock.ui.theme.DockColors
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.remote.RemoteTask
import com.wangzan101.mika.core.remote.RemoteTaskService
import com.wangzan101.mika.task.DockRoute
import com.wangzan101.mika.task.DockRoute.Phase
import com.wangzan101.mika.task.DockRoutes
import com.wangzan101.mika.ui.MarkdownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

internal fun Context.openRoutes(goal: String = "", id: String? = null) = startActivity(
    Intent(this, MikaTasksActivity::class.java).putExtra("routes", true).putExtra("goal", goal).putExtra("routeId", id))
internal fun Context.startRouteTrial(goal: String) = openRoutes(id = DockRoutes(this).trial(goal))

/** 订阅同一条后台记录；用户回到空间、模板或结果页时看到相同状态。 */
@Composable internal fun rememberedRoutes(reconcileBusy: Boolean = true): List<DockRoute> {
    val context = LocalContext.current
    val store = remember { DockRoutes(context) }
    var rows by remember { mutableStateOf(store.all()) }
    DisposableEffect(store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> rows = store.all() }
        store.prefs.registerOnSharedPreferenceChangeListener(listener)
        rows = store.all()
        onDispose { store.prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val pending = rows.filter { it.busy }.map { it.taskId }.sorted()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(store, pending, reconcileBusy, lifecycle) {
        if (reconcileBusy && pending.isNotEmpty()) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            do {
                val active = withContext(Dispatchers.IO) { store.reconcileBusy(pending) }
                if (active) delay(1000)
            } while (active)
        }
    }
    return rows.sortedByDescending { maxOf(it.resultAt, it.sampleAt) }
}

private fun DockRoute.status() = if (stopping) "正在停止" else when (phase) {
    Phase.Draft -> "待试查"
    Phase.Stopped -> "已停止"
    Phase.Trial -> "正在后台试查"
    Phase.Scope -> "待确认查询范围"
    Phase.Checking -> "正在独立验证路线"
    Phase.Ready -> when {
        isTemplate -> "已验证"
        saved && resultAt > 0 -> "本次查询完成"
        saved -> "待运行"
        else -> "路线验证通过"
    }
    Phase.Running -> if (openPageOnRun) "正在定位，随后打开目标页" else "Mika 正在重新查询"
    Phase.Failed -> "本次未完成"
}

/** 成功和未完成同时用文字与底色区分；状态由服务回执驱动。 */
@Composable private fun RouteStatus(row: DockRoute) {
    val color = when (row.phase) {
        Phase.Ready -> DockColors.Sage
        Phase.Failed -> DockColors.Error
        else -> DockColors.Amber
    }
    Surface(color = color.copy(alpha = .10f), shape = RoundedCornerShape(8.dp)) {
        Text(row.status(), Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = color,
            style = MaterialTheme.typography.labelLarge)
    }
}

/** 票面来源采用实际启动包的应用名称，便于用户核对本次路线。 */
@Composable private fun RouteSource(row: DockRoute) {
    val pkg = row.sourcePackage ?: row.route?.packages?.lastOrNull() ?: return
    val manager = LocalContext.current.packageManager
    val name = remember(pkg, manager) {
        try { manager.getApplicationLabel(manager.getApplicationInfo(pkg, 0)).toString() }
        catch (_: android.content.pm.PackageManager.NameNotFoundException) { pkg }
    }
    Text("来源 · $name", style = MaterialTheme.typography.bodySmall, color = DockColors.Muted)
}

private fun resultTime(row: DockRoute) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(row.resultAt))

/** 详情页中的返回操作指向对应实体。 */
internal val DockRoute.detailBackLabel: String
    get() = when {
        isTemplate -> "返回模板"
        isCard -> "返回卡片"
        else -> "返回草稿"
    }

/** 空间点卡直接执行；模板库打开配置页。正在执行时只查看已有回执。 */
@Composable private fun priceNoticeToggle(row: DockRoute?, store: DockRoutes): () -> Unit {
    val context = LocalContext.current
    val grant = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && row != null) store.track(row.id, row.daily, true)
    }
    return {
        if (row != null) {
            val enabled = context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()
            if (row.notifyDrop || enabled) store.track(row.id, row.daily, !row.notifyDrop)
            else if (android.os.Build.VERSION.SDK_INT >= 33) grant.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            else context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
    }
}

@Composable internal fun RouteTicket(row: DockRoute, enabled: Boolean, library: Boolean = false) {
    val context = LocalContext.current
    val store = remember { DockRoutes(context) }
    val toggleNotice = priceNoticeToggle(row, store)
    var parameters by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    if (editing) RouteParameters(row, true, { editing = false }) { values, ask ->
        store.configure(row.id, values, ask)
        editing = false
    }
    if (removing) RouteRemoval(row, { removing = false }) {
        removing = false
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { store.stop(row.id, delete = true, expectedTaskId = row.taskId) } }
                .onFailure { problem = it.localizedMessage.orEmpty() }
        }
    }
    fun open() {
        if (!library && row.saved && !row.busy && row.askEachTime && row.slots.isNotEmpty()) parameters = true
        else {
            if (!library && row.saved && !row.busy) store.execute(row.id)
            context.openRoutes(id = row.id)
        }
    }
    if (parameters) RouteParameters(row, false, { parameters = false }) { values, _ ->
        parameters = false
        store.execute(row.id, values)
        context.openRoutes(id = row.id)
    }
    Surface(onClick = ::open, enabled = enabled, shape = RoundedCornerShape(16.dp),
        color = DockColors.Ticket, border = BorderStroke(1.dp, DockColors.Outline), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (row.isCard && row.price != null && row.resultAt > 0) {
                val quote = requireNotNull(row.price)
                Text("${quote.display} · ${quote.key.label}", style = MaterialTheme.typography.titleMedium)
                Text((if (row.daily) "每日核价 · " else "") + "上次核实 " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(row.resultAt)) +
                    row.previousPrice?.let(quote::changeFrom)?.let { " · $it" }.orEmpty(), style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    RouteStatus(row)
                    Text(row.demand(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Box {
                    IconButton({ menu = true }) { Icon(DockIcons.MoreHoriz, "${row.demand()}的更多操作") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("查看详情") }, onClick = { menu = false; context.openRoutes(id = row.id) })
                        if (row.taskId.isNotEmpty()) DropdownMenuItem(text = { Text("查看执行过程") },
                            onClick = {
                                menu = false
                                val back = if (!library) "返回卡片空间" else if (row.isTemplate) "返回模板库" else "返回模板草稿"
                                context.openTaskDetail(row.taskId, back, row.id)
                            })
                        if (row.isCard && !row.busy && row.cardIntent?.price != true) DropdownMenuItem(
                            text = { Text(if (row.openPageOnRun) "改为后台查询" else "改为运行后打开目标页") },
                            onClick = { menu = false; store.setOpenPageOnRun(row.id, !row.openPageOnRun) })
                        if (row.isCard && row.cardIntent?.price == true) {
                            DropdownMenuItem(text = { Text(if (row.daily) "停止每日核价" else "开启每日核价") },
                                onClick = { menu = false; store.track(row.id, !row.daily, row.notifyDrop) })
                            DropdownMenuItem(text = { Text(if (row.notifyDrop) "关闭降价提醒" else "开启降价提醒") },
                                onClick = { menu = false; toggleNotice() })
                        }
                        if (row.saved && !row.busy && row.slots.isNotEmpty()) DropdownMenuItem(text = { Text("编辑参数") },
                            onClick = { menu = false; editing = true })
                        if (row.busy && !row.stopping) DropdownMenuItem(text = { Text("取消任务") }, onClick = {
                            menu = false
                            scope.launch { runCatching { withContext(Dispatchers.IO) { store.stop(row.id, expectedTaskId = row.taskId) } }
                                .onFailure { problem = it.localizedMessage.orEmpty() } }
                        })
                        DropdownMenuItem(text = { Text(if (row.busy) "停止并删除" else if (row.isCard) "删除卡片" else if (row.isTemplate) "删除模板" else "删除草稿") }, onClick = { menu = false; removing = true })
                    }
                }
            }
            RouteSource(row)
            HorizontalDivider(color = DockColors.Amber.copy(alpha = .25f))
            if (row.result.isNotBlank()) {
                Text(row.result, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("上次核对 · " + resultTime(row), style = MaterialTheme.typography.bodySmall)
            }
            if (row.issue.isNotBlank()) Text(row.issue, color = DockColors.Error, maxLines = 2)
            if (row.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(::open, enabled = enabled, shape = DockButtonShape, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (row.busy) "查看进度" else if (library || !row.saved) "查看详情" else if (row.openPageOnRun) "运行并打开目标页" else "重新查询")
            }
            if (problem.isNotBlank()) Text(problem, color = DockColors.Error)
        }
    }
}

/** 单次确认同时说明停止和删除后果；实例、模板和草稿各自独立。 */
@Composable private fun RouteRemoval(row: DockRoute, dismiss: () -> Unit, confirm: () -> Unit) {
    val type = if (row.isTemplate) "模板" else if (row.isCard) "卡片" else "草稿"
    AlertDialog(dismiss, title = { Text(if (row.busy) "停止任务并删除$type？" else "删除$type？") },
        text = { Text(if (row.isTemplate) "删除后，已创建的卡片仍可使用。" else
            (if (row.busy) "当前任务将停止。" else "") + "将移除这份${type}及其已存结果。") },
        confirmButton = { TextButton(confirm) { Text(if (row.busy) "停止并删除" else "删除") } },
        dismissButton = { TextButton(dismiss) { Text("取消") } })
}

@Composable internal fun RouteScreen(tasks: RemoteTaskService, initialId: String?, initialGoal: String, close: () -> Unit, returnToSpace: (Boolean) -> Unit) {
    val context = LocalContext.current
    val store = remember { DockRoutes(context) }
    val routeActions = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var id by rememberSaveable { mutableStateOf(initialId) }
    var goal by rememberSaveable { mutableStateOf(initialGoal) }
    val rows = rememberedRoutes(reconcileBusy = false)
    val row = rows.find { it.id == id }
    val toggleNotice = priceNoticeToggle(row, store)
    val chooseIdentityScope = row?.phase == Phase.Scope && row.cardIntent != com.wangzan101.mika.core.agent.DockCardIntent.IdentityPage && row.identityPageOnlySample && !LearnGate.identityArrivalOnly(row.goal)
    var scopeText by rememberSaveable(id, row?.sampleAt) { mutableStateOf(
        row?.scope?.takeIf { it.isNotBlank() }
            ?: row?.price?.let { "核对${it.key.product}，已选规格${it.key.variant}的${it.key.label}，每次读取当时的价格。" }
            ?: row?.sample?.lineSequence()?.lastOrNull { it.trimStart('*', ' ').startsWith("复查规则：") }
                ?.substringAfter("复查规则：")?.trim(' ', '*')?.take(800)?.takeIf { it.isNotBlank() }
            ?: if (row?.route?.packages?.contains("com.cainiao.wireless") == true && row.goal.contains("淘宝"))
                "在菜鸟待取件页查询当前待取包裹及取件码，页面显示暂无待取时报告为空。"
            else row?.goal.orEmpty()) }
    var continuing by rememberSaveable(id) { mutableStateOf(false) }
    var direction by rememberSaveable(id) { mutableStateOf("") }
    var task by remember(row?.taskId) { mutableStateOf<RemoteTask?>(null) }
    var askId by remember(row?.taskId) { mutableStateOf<String?>(null) }
    var reply by rememberSaveable(row?.taskId, askId) { mutableStateOf("") }
    var issue by remember(row?.taskId) { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var editingGoal by rememberSaveable(id) { mutableStateOf(false) }
    var updatedGoal by rememberSaveable(id) { mutableStateOf("") }
    var asking by remember { mutableStateOf(false) }
    LaunchedEffect(row?.taskId, row?.busy, lifecycle) {
        val current = row?.takeIf { it.taskId.isNotBlank() } ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            do {
                val receipt = withContext(Dispatchers.IO) { runCatching {
                    if (current.busy) store.reconcile(current.taskId) else tasks.get(current.taskId)
                } }
                task = receipt.getOrNull()
                val prompt = (task?.state as? TaskState.WaitingInput)?.prompt
                askId = if (prompt == null) null else (context.applicationContext as com.wangzan101.mika.MikaApp)
                    .taskState.runs.value.firstOrNull { it.mcpExternalId == current.taskId }
                    ?.activeAsk?.takeIf { it.text == prompt }?.askId
                if (receipt.isFailure) issue = "暂时无法读取 Mika 回执"
                if (!current.busy || store.get(current.id)?.busy != true) break
                delay(900)
            } while (true)
        }
    }
    // 已有详情链接丢失时，允许从原始自由任务回执重新导入，避免误开一张空白卡片。
    val missingId = id
    if (row == null && missingId != null) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(close) { Text("返回卡坞") }
            Text("未找到这条路线", style = MaterialTheme.typography.headlineMedium)
            if (missingId.startsWith("free:")) {
                var restoring by remember(missingId) { mutableStateOf(false) }
                Button(enabled = !restoring, onClick = {
                    restoring = true
                    routeActions.launch {
                        try {
                            withContext(Dispatchers.IO) { store.fromFree(tasks.get(missingId.removePrefix("free:"))) }
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { issue = e.localizedMessage ?: "无法读取原任务回执。" }
                        finally { restoring = false }
                    }
                }) { Text("从原任务恢复候选模板") }
            }
            if (issue.isNotBlank()) Text(issue, color = DockColors.Error)
        }
        return
    }
    if (row != null && (editing || asking)) RouteParameters(row, editing, { editing = false; asking = false }) { values, ask ->
        if (editing) store.configure(row.id, values, ask)
        else if (row.isTemplate) id = store.createAndRun(row.id, values)
        else store.execute(row.id, values)
        editing = false; asking = false
    }
    if (removing && row != null) RouteRemoval(row, { removing = false }) {
        removing = false
        routeActions.launch {
            runCatching { withContext(Dispatchers.IO) { store.stop(row.id, delete = true, expectedTaskId = row.taskId) } }
                .onSuccess { close() }.onFailure { issue = it.localizedMessage.orEmpty() }
        }
    }
    if (editingGoal && row != null) AlertDialog({ editingGoal = false }, title = { Text("修改查询需求") },
        text = { OutlinedTextField(updatedGoal, { updatedGoal = it }, label = { Text("查询需求") }, minLines = 3) },
        confirmButton = { TextButton({
            if (row.saved) id = store.trial(updatedGoal.trim(), templateId = if (row.isTemplate) row.id else row.templateId)
            else store.restartTrial(row.id, updatedGoal)
            editingGoal = false
        }, enabled = updatedGoal.trim().length in 1..RemoteTaskService.MAX_GOAL) { Text("开始试查") } },
        dismissButton = { TextButton({ editingGoal = false }) { Text("取消") } })

    Surface(color = DockColors.Background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(close) { Text("返回卡坞") }
                Text("Mika", color = DockColors.Muted, style = MaterialTheme.typography.labelLarge)
            }
            HorizontalDivider(color = DockColors.Outline)
            Text(when {
                row == null -> "新建卡片"
                row.isTemplate -> "模板详情"
                row.saved -> "卡片详情"
                row.phase == Phase.Draft -> "需求草稿"
                row.busy -> "后台试查"
                else -> "试查结果"
            }, modifier = Modifier.padding(top = 16.dp), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (row != null && row.sourceChoices.isNotEmpty()) {
                    Text("从哪个应用打开官方取件页？")
                    row.sourceChoices.forEach { pkg ->
                        val name = context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
                        OutlinedButton({ store.chooseSource(row.id, pkg) }) { Text(name) }
                    }
                }
                if (row != null && row.phase == Phase.Scope) row.cardIntent?.let { kind ->
                    Text(kind.summary, style = MaterialTheme.typography.titleSmall)
                    row.price?.let { Text("${it.key.product} · ${it.key.variant} · ${it.key.label} ${it.display}") }
                    if (kind.price) {
                        FilterChip(row.daily, { store.track(row.id, !row.daily, row.notifyDrop) }, { Text("每日自动核价") })
                        FilterChip(row.notifyDrop, toggleNotice, { Text("降价提醒") })
                    }
                }
                if (row == null) {
                    Text("你想让 Mika 查什么？", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(goal, { goal = it }, minLines = 4, shape = DockButtonShape,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "查询需求" },
                        placeholder = { Text("例如，打开时钟，看看首页有哪些标签。") })
                    Text("可以切去使用其他应用，试查进度会保留在卡坞。", color = DockColors.Muted)
                } else {
                    RouteStatus(row)
                    Text(row.demand(), style = MaterialTheme.typography.titleMedium)
                    if (!row.saved && row.busy && !row.stopping) {
                        // 步骤只根据持久化阶段点亮，避免把模型的意图当成已完成的操作。
                        Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("✓   已提交需求", color = DockColors.Sage)
                            Text(if (row.phase == Phase.Checking) "✓   首次试查已取得答复" else "●   Mika 正在试做并读取结果",
                                color = if (row.phase == Phase.Checking) DockColors.Sage else DockColors.Mint)
                            Text(if (row.phase == Phase.Checking) "●   从应用入口重新验证" else "○   等待验证路线",
                                color = if (row.phase == Phase.Checking) DockColors.Mint else DockColors.Muted)
                        }
                    }
                    if (row.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (row.issue.isNotBlank()) Text(row.issue, color = DockColors.Error)
                    val state = task?.state
                    if (row.busy && !row.stopping && (state == null || state == TaskState.Idle ||
                        state is TaskState.Running && state.step == 0)) {
                        val shell by com.wangzan101.mika.cap.phone.PhoneServices.of(context).shellControl.status.collectAsStateWithLifecycle()
                        if (shell == com.wangzan101.mika.core.shell.BuiltinShellStatus.Connecting) Text("正在连接手机助手")
                    }
                    if (row.busy && !row.stopping) when (state) {
                        is TaskState.Running -> Text(state.narration ?: state.note)
                        is TaskState.WaitingInput -> {
                            val question = askId
                            fun respond(value: String) {
                                if (question == null) return
                                routeActions.launch {
                                    runCatching { withContext(Dispatchers.IO) { store.answer(row.id, value, row.taskId, question) } }
                                        .onSuccess { reply = "" }.onFailure { issue = it.localizedMessage.orEmpty() }
                                }
                            }
                            Text(state.prompt)
                            state.options.orEmpty().forEach { option ->
                                OutlinedButton({ respond(option) }, enabled = question != null) { Text(option) }
                            }
                            OutlinedTextField(reply, { reply = it }, label = { Text("补充给 Mika") })
                            TextButton({ respond(reply) }, enabled = question != null && reply.trim().length in 1..RemoteTaskService.MAX_ANSWER) {
                                Text("发送回复")
                            }
                        }
                        is TaskState.Held -> Text(state.reason.why)
                        else -> Unit
                    }
                    if (row.result.isNotBlank()) {
                        Surface(color = DockColors.Ticket, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(if (row.isTemplate) "路线复验结果" else if (row.phase == Phase.Ready && row.resultGoal == row.demand()) "本次结果" else "上次成功结果",
                                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                RouteSource(row)
                                Text("核对时间 · " + resultTime(row), style = MaterialTheme.typography.bodySmall)
                                HorizontalDivider(color = DockColors.Amber.copy(alpha = .3f))
                                if (row.resultGoal != row.demand()) Text(row.resultGoal, color = DockColors.Muted)
                                MarkdownText(row.result, MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    if (row.lastAnswer.isNotBlank()) {
                        Text("本次答复 · 未通过复验", style = MaterialTheme.typography.titleSmall)
                        MarkdownText(row.lastAnswer, MaterialTheme.typography.bodyLarge)
                    }
                    if (row.sample.isNotBlank() && !row.saved) {
                        Text(if (row.sampleVerified) "试查结果 · 已核实" else "试查答复", style = MaterialTheme.typography.titleSmall)
                        RouteSource(row)
                        if (row.sampleAt > 0) Text(DateFormat.getDateTimeInstance().format(Date(row.sampleAt)), style = MaterialTheme.typography.bodySmall)
                        MarkdownText(row.sample, MaterialTheme.typography.bodyLarge)
                    }
                    if (row.phase == Phase.Scope) {
                        RouteSource(row)
                        if (chooseIdentityScope) {
                            Text("请选择下次运行需要核实的结果。")
                            OutlinedButton({ runCatching { store.confirmScope(row.id, LearnGate.IDENTITY_ARRIVAL_SCOPE) }
                                .onFailure { issue = it.localizedMessage.orEmpty() } }) { Text("只验证打开身份码页") }
                            OutlinedButton({ runCatching { store.continueExploration(row.id, "请继续核实身份码是否确实展示。") }
                                .onFailure { issue = it.localizedMessage.orEmpty() } }) { Text("继续查明展示") }
                        } else if (row.cardIntent?.price == true && row.price != null) {
                            Text(scopeText)
                            TextButton({ continuing = true }) { Text("更换商品、规格或价格口径") }
                        } else OutlinedTextField(scopeText, { scopeText = it }, label = { Text("每次运行使用的查询口径") },
                            modifier = Modifier.fillMaxWidth(), minLines = 2)
                    } else if (row.scope.isNotBlank()) Text("复查口径 · " + row.scope)
                    if (!row.saved && !row.busy && row.phase != Phase.Draft) {
                        TextButton({ continuing = !continuing }) { Text("补充需求") }
                        if (continuing) {
                            OutlinedTextField(direction, { direction = it }, label = { Text("还希望 Mika 做什么，或刚才哪里不符合预期？") })
                            TextButton({ runCatching { store.continueExploration(row.id, direction.trim()); continuing = false }.onFailure { issue = it.localizedMessage.orEmpty() } }, enabled = direction.isNotBlank()) { Text("补充并重新试查") }
                        }
                    }
                    if (!row.busy) TextButton({ updatedGoal = row.demand(); editingGoal = true }) { Text("修改需求并试查") }
                    if (!row.saved && row.phase in setOf(Phase.Failed, Phase.Scope) &&
                        (row.route != null || task?.state is TaskState.Succeeded))
                        OutlinedButton({ routeActions.launch {
                            runCatching { withContext(Dispatchers.IO) { store.recoverCandidate(row.id) } }
                                .onFailure { issue = it.localizedMessage.orEmpty() }
                        } }) { Text("从原试查重新提取路线") }
                    val newer = rows.find { it.id == row.templateId && it.isTemplate }
                    if (row.saved && !row.busy && newer != null && newer.route?.id != row.route?.id)
                        TextButton({ store.adopt(row.id) }) { Text("采用新路线及默认参数") }
                    if (row.phase == Phase.Ready && !row.saved) {
                        HorizontalDivider()
                        Text("以后点这张卡", style = MaterialTheme.typography.titleMedium)
                        Text("Mika 将重新执行「" + row.demand() + "」，并核对当次结果。")
                    }
                    if (row.taskId.isNotEmpty()) TextButton({ context.openTaskDetail(row.taskId, row.detailBackLabel, row.id) }) {
                        Text("查看执行过程")
                    }
                    // 复用已验收的原生搭配和报价流程，只有该流程中的单份确认才会更改购物车。
                    if (row.saved && !row.isTemplate && row.phase == Phase.Ready && row.route?.packages?.contains(com.life0423.carddock.ai.MCD_PACKAGE) == true) {
                        OutlinedButton({
                            val demand = row.demand()
                            val meal = com.life0423.carddock.core.rule.MealTarget(demand, searchByDescription = true)
                            val controller = (context.applicationContext as com.life0423.carddock.CardDockApplication).container.controller
                            val active = controller.state.value
                            if (active.loading || active.sequence != null || active.draft?.let { it.prompt != demand || it.phase.isBusy() } == true) {
                                issue = "请先回到卡坞完成当前编辑或核验，再选择搭配。"
                                return@OutlinedButton
                            }
                            val actions = controller.actions
                            actions.openCreator(com.life0423.carddock.ai.cartPlan(demand, meal).choices.single())
                            actions.trialDraft()
                            context.startActivity(Intent(context, com.life0423.carddock.MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                            close()
                        }) { Text("选择搭配并核对加购价") }
                    }
                    if (row.saved && !row.isTemplate && !row.busy && row.slots.isNotEmpty()) TextButton({ editing = true }) { Text("编辑卡片参数") }
                    if (row.phase == Phase.Failed && !row.saved && row.route != null) {
                        TextButton({ routeActions.launch {
                            runCatching { withContext(Dispatchers.IO) { store.recheck(row.id) } }
                                .onFailure { issue = it.localizedMessage.orEmpty() }
                        } }) { Text("重新验证路线") }
                        if (store.alternativeFor(row, rows) != null) TextButton({
                            routeActions.launch {
                                try { withContext(Dispatchers.IO) { store.recheckWithAlternative(row.id) } }
                                catch (e: IllegalStateException) { issue = e.localizedMessage.orEmpty() }
                            }
                        }) { Text("改用同需求的已存路线复验") }
                    }
                    if (row.busy) TextButton({ routeActions.launch {
                        runCatching { withContext(Dispatchers.IO) { store.stop(row.id, expectedTaskId = row.taskId) } }
                            .onFailure { issue = it.localizedMessage.orEmpty() }
                    } }, enabled = !row.stopping) { Text(if (row.stopping) "正在停止" else "取消任务") }
                    TextButton({ removing = true }) { Text(when {
                        row.busy -> "停止并删除"
                        row.isTemplate -> "删除模板"
                        row.saved -> "删除卡片"
                        else -> "删除草稿"
                    }) }
                }
                if (issue.isNotBlank()) Text(issue, color = DockColors.Error)
            }
            val label = when {
                row == null -> "让 Mika 试查"
                row.busy -> if (row.saved) "返回卡片空间" else "返回模板草稿"
                row.phase == Phase.Draft -> "让 Mika 试查"
                row.phase == Phase.Scope -> "按此口径验证模板"
                row.isTemplate -> "创建并运行"
                !row.saved && row.phase == Phase.Ready -> "保存路线模板"
                !row.saved && row.phase in setOf(Phase.Failed, Phase.Stopped) -> "按原需求重新试查"
                row.openPageOnRun -> "运行并打开目标页"
                else -> "重新查询"
            }
            if (!chooseIdentityScope && row?.sourceChoices.orEmpty().isEmpty()) Button(onClick = { when {
                row == null -> id = store.trial(goal.trim())
                row.busy -> returnToSpace(!row.saved)
                row.phase == Phase.Draft -> store.restartTrial(row.id, row.goal)
                row.phase == Phase.Scope -> runCatching { store.confirmScope(row.id, scopeText) }.onFailure { issue = it.localizedMessage.orEmpty() }
                row.isTemplate -> if (row.slots.isEmpty()) id = store.createAndRun(row.id, emptyList()) else asking = true
                !row.saved && row.phase == Phase.Ready -> {
                    saving = true
                    routeActions.launch {
                        try { id = withContext(Dispatchers.IO) { store.save(row.id) } }
                        catch (e: IllegalStateException) { issue = e.localizedMessage.orEmpty() }
                        finally { saving = false }
                    }
                }
                !row.saved && row.phase in setOf(Phase.Failed, Phase.Stopped) -> store.restartTrial(row.id, row.originalGoal ?: row.goal)
                row.askEachTime && row.slots.isNotEmpty() -> asking = true
                else -> store.execute(row.id)
            } }, enabled = !saving && (if (row?.phase == Phase.Scope) scopeText.isNotBlank() && scopeText.length <= 800 else row != null || goal.trim().length in 1..RemoteTaskService.MAX_GOAL), shape = DockButtonShape,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).heightIn(min = 48.dp)) { Text(label) }
        }
    }
}

/** 使用路线记录时绑定的槽位；默认沿用卡片参数，可为有槽位的卡片打开每次询问。 */
@Composable private fun RouteParameters(row: DockRoute, edit: Boolean, close: () -> Unit, submit: (List<String>, Boolean) -> Unit) {
    var values by remember(row) { mutableStateOf(row.slots) }
    var ask by remember(row) { mutableStateOf(row.askEachTime) }
    AlertDialog(close, title = { Text(if (edit) "编辑卡片参数" else "本次查询参数") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(row.demand(values))
            if (values.isEmpty()) Text("这条路线使用固定查询参数。", color = DockColors.Muted)
            values.forEachIndexed { i, v -> OutlinedTextField(v, { next ->
                values = values.toMutableList().also { it[i] = next }
            }, label = { Text("参数 " + (i + 1)) }) }
            if (edit && values.isNotEmpty()) Row { Checkbox(ask, { ask = it }); Text("每次执行前询问") }
        }
    }, confirmButton = { TextButton({ submit(values.map(String::trim), ask) }, enabled = values.none(String::isBlank) &&
        row.demand(values).length <= RemoteTaskService.MAX_GOAL) { Text(if (edit) "保存" else "开始查询") } },
        dismissButton = { TextButton(close) { Text("取消") } })
}
