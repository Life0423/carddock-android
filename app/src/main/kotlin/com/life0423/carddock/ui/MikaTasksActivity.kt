package com.life0423.carddock.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import com.life0423.carddock.ui.theme.CardDockTheme
import com.life0423.carddock.MainActivity
import com.life0423.carddock.CardDockApplication
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.remote.RemoteTask
import com.wangzan101.mika.core.remote.RemoteTaskService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** 卡坞内的自由操作入口。只有用户点执行才新建任务，打开页面和恢复进度都只读。 */
internal class MikaTasksActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        com.wangzan101.mika.quit.MikaQuit.resume(this)
    }

    /** 页面上的显式返回空间动作可以跨过自由操作 Activity，同时保留旧编辑器的需求草稿。 */
    private fun returnToSpace(drafts: Boolean) {
        val controller = (application as CardDockApplication).container.controller
        controller.state.value.draft?.takeIf { it.personalTemplateId != null }?.let { draft ->
            if (draft.prompt.isBlank() && draft.title.isBlank() && draft.template == null) controller.actions.closeCreator()
            else controller.actions.keepTemplateDraft()
        }
        startActivity(Intent(this, MainActivity::class.java).setAction(if (drafts) MainActivity.SHOW_ROUTE_DRAFTS else MainActivity.SHOW_ROUTE_SPACE)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tasks = RemoteTaskService((application as MikaApp).remoteTasks.localTasks)
        setContent {
            CardDockTheme {
                DockActionTheme {
                    if (intent.getBooleanExtra("routes", false)) RouteScreen(tasks, intent.getStringExtra("routeId"),
                        intent.getStringExtra("goal").orEmpty(), ::finish, ::returnToSpace)
                    else MikaTaskScreen(tasks, intent.getStringExtra("goal").orEmpty(), ::finish)
                }
            }
        }
    }
}

internal fun Context.openFreeTask(goal: String = "") {
    startActivity(Intent(this, MikaTasksActivity::class.java).putExtra("goal", goal))
}

@Composable
private fun MikaTaskScreen(tasks: RemoteTaskService, initialGoal: String, close: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val receipt = remember { context.getSharedPreferences("mika_submission", Context.MODE_PRIVATE) }
    var requestId by rememberSaveable { mutableStateOf(receipt.getString("id", null)) }
    var goal by rememberSaveable { mutableStateOf(receipt.getString("goal", null) ?: initialGoal) }
    var rows by remember { mutableStateOf(emptyList<RemoteTask>()) }
    var pending by remember { mutableStateOf(false) }
    var issue by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }

    fun operate(action: suspend () -> Unit) {
        if (pending) return
        pending = true
        issue = ""
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { issue = e.localizedMessage ?: "任务服务暂时不可用。" }
            finally { pending = false; refresh++ }
        }
    }

    // 后台由 TaskService 持有任务；界面只在可见时读取同一个持久化回执。
    LaunchedEffect(lifecycle, refresh) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try { rows = withContext(Dispatchers.IO) { tasks.list(20) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { issue = e.localizedMessage ?: "无法读取任务记录。" }
        }
    }

    Surface(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(),
            contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                TextButton(onClick = close) { Text("返回卡坞") }
                Text("自由操作", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
            item {
                OutlinedTextField(goal, { goal = it }, enabled = !pending && requestId == null, modifier = Modifier.fillMaxWidth(),
                    label = { Text("想让 Mika 做什么？") }, minLines = 3)
            }
            item {
                Button(onClick = {
                    val submitted = goal.trim()
                    val id = requestId ?: UUID.randomUUID().toString()
                    requestId = id
                    operate {
                        val result = withContext(Dispatchers.IO) {
                            check(receipt.edit().putString("id", id).putString("goal", submitted).commit()) { "无法保存任务编号。" }
                            tasks.start(submitted, requestId = id)
                        }
                        receipt.edit().clear().apply()
                        requestId = null
                        goal = ""
                        context.openTaskDetail(result.id)
                    }
                }, enabled = goal.isNotBlank() && goal.trim().length <= RemoteTaskService.MAX_GOAL && !pending, shape = DockButtonShape,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (requestId != null) "恢复这次提交" else "交给 Mika 执行") }
                if (goal.trim().length > RemoteTaskService.MAX_GOAL) Text("需求最多 4000 字。")
                if (requestId != null && !pending) {
                    TextButton({
                        receipt.edit().clear().apply()
                        requestId = null
                        goal = ""
                        issue = ""
                    }) { Text("放下草稿，查看最近任务") }
                }
            }
            if (rows.isNotEmpty()) item { Text("最近任务", style = MaterialTheme.typography.titleMedium) }
            items(rows, key = { it.id }) { task ->
                OutlinedButton({ context.openTaskDetail(task.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text(task.goal, maxLines = 2)
                }
            }
            if (issue.isNotBlank()) item { Text(issue, color = MaterialTheme.colorScheme.error) }
        }
    }
}
