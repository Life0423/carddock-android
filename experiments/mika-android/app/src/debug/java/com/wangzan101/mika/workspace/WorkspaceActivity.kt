package com.wangzan101.mika.workspace

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class WorkspaceActivity : ComponentActivity() {
    private val vm: WorkspaceViewModel by viewModels()
    private var pendingAction: Intent? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val s by vm.state.collectAsState()
            var tab by rememberSaveable { mutableStateOf(0) }
            var deleting by remember { mutableStateOf<WorkspaceCard?>(null) }
            var preview by rememberSaveable { mutableStateOf(false) }
            val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                        Text("功能重组器", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(20.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            listOf("空间", "模板", "我的").forEachIndexed { i, name ->
                                TextButton(onClick = { tab = i; vm.select(null) }) { Text(if (tab == i) "● $name" else name) }
                            }
                        }
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (tab == 0) {
                                val detail = s.cards.find { it.id == s.selected }
                                if (detail != null) {
                                    TextButton(onClick = { vm.select(null) }) { Text("返回空间") }
                                    Text(detail.title, style = MaterialTheme.typography.titleLarge)
                                    Text("来源：${detail.source}\n规则：${detail.ruleId} v${detail.ruleVersion}\n卡片 ID：${detail.id}")
                                    Text("上次成功结果：${detail.lastValidText}\n核验时间：${time.format(Date(detail.lastSuccessAt))}\n服务器更新时间未知")
                                    Text("最近尝试：${time.format(Date(detail.lastAttemptAt))}\n${detail.reason}")
                                    Text("运行记录", style = MaterialTheme.typography.titleMedium)
                                    s.history.forEach { Text("${time.format(Date(it.finishedAt))} · ${it.outcome}\n${it.elapsedMs} ms · 模型请求增量 ${it.modelCallsDelta}\n${it.message}") }
                                } else {
                                    Text("我的日常空间", style = MaterialTheme.typography.titleLarge)
                                    if (s.cards.isEmpty()) Text("还没有卡片。到模板页匹配需求，通过试运行后确认创建。")
                                    Button(onClick = { vm.refresh() }, enabled = s.ready && !s.busy && s.cards.isNotEmpty()) { Text("顺序刷新全部") }
                                    s.cards.forEach { card ->
                                        Card(Modifier.fillMaxWidth()) {
                                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text(card.title, style = MaterialTheme.typography.titleMedium)
                                                Text(when (card.status) {
                                                    CardStatus.FRESH_VALID -> "已核验"
                                                    CardStatus.REFRESHING -> "刷新中 · 显示上次结果"
                                                    CardStatus.STALE_CACHED -> "旧缓存 · 本次未核验"
                                                    CardStatus.NEED_HANDOFF -> "需要手动处理 · 显示上次结果"
                                                    CardStatus.NEED_HEAL -> "规则需要检查 · 显示上次结果"
                                                })
                                                Text(card.lastValidText, style = MaterialTheme.typography.titleLarge)
                                                Text("来源：${card.source}\n上次核验：${time.format(Date(card.lastSuccessAt))}\n最近尝试：${time.format(Date(card.lastAttemptAt))}")
                                                Text(card.reason)
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    Button(onClick = { vm.refresh(card.id) }, enabled = !s.busy) { Text("刷新") }
                                                    TextButton(onClick = { vm.select(card.id) }) { Text("详情") }
                                                    TextButton(onClick = { deleting = card }, enabled = !s.busy) { Text("删除") }
                                                }
                                            }
                                        }
                                    }
                                }
                            } else if (tab == 1) {
                                Text("新建菜鸟待取卡片", style = MaterialTheme.typography.titleLarge)
                                Text("模板匹配（Mock）：当前只支持菜鸟明确空状态，不调用模型。有包裹时会提示无法核验，不会生成取件码。")
                                OutlinedTextField(value = s.request, onValueChange = vm::editRequest, enabled = !s.busy, label = { Text("你的需求") }, modifier = Modifier.fillMaxWidth())
                                TextButton(onClick = { vm.editRequest(MockCompiler.template) }, enabled = !s.busy) { Text("使用内置需求：查看菜鸟待取包裹") }
                                Button(onClick = vm::compile, enabled = s.ready && !s.busy) { Text("匹配模板，生成候选规则") }
                                if (s.candidateJson.isNotEmpty()) {
                                    Text("菜鸟 8.11.1105 · 首页 → 取包裹 → 核验标题与空状态")
                                    TextButton(onClick = { preview = !preview }) { Text(if (preview) "收起规则" else "查看候选 JSON") }
                                    if (preview) Text(s.candidateJson, style = MaterialTheme.typography.bodySmall)
                                    Button(onClick = vm::dryRun, enabled = !s.busy) { Text("真实试运行") }
                                    Button(onClick = { vm.confirm(); tab = 0 }, enabled = s.canConfirm && !s.busy) { Text("结果正确，确认创建卡片") }
                                }
                            } else {
                                Text("第一阶段 · 单数据源 MVP", style = MaterialTheme.typography.titleLarge)
                                Text("规则、卡片和运行记录通过 Room 保存在本机。普通刷新读取已验证规则，不需要配置模型。")
                                Text("数据源：菜鸟 8.11.1105\n能力：核验“暂无待取包裹”\n范围：前台顺序执行；无后台定时刷新、真实 AI 编译或自动修复。")
                                Text("请在手机空闲时启动查询。切换到其他应用后，查询不会在那里点击，也不会自动覆盖该应用。")
                                TextButton(onClick = { startActivity(Intent(this@WorkspaceActivity, com.wangzan101.mika.experiment.CainiaoExperimentActivity::class.java)) }, enabled = !s.busy) { Text("打开规则实验工具") }
                            }
                            HorizontalDivider()
                            Text(s.message)
                            if (s.busy) OutlinedButton(onClick = vm::cancel) { Text("取消查询") }
                            Text("${s.outcome} · 步骤 ${s.step}\n最近查询 ${s.elapsedMs} ms · 模型请求增量 ${s.modelCallsDelta}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    deleting?.let { card ->
                        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除这张卡片？") },
                            text = { Text("将删除此卡片及其运行记录。其他卡片保留。") },
                            confirmButton = { TextButton(onClick = { vm.delete(card.id); deleting = null }) { Text("删除") } },
                            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
                    }
                }
            }
        }
        if (savedInstanceState == null) acceptDebugAction(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptDebugAction(intent)
    }

    /** Debug-only entry; invokes the same gate and operations as visible controls. */
    private fun acceptDebugAction(intent: Intent) {
        if (!intent.action.orEmpty().startsWith("com.wangzan101.mika.workspace.")) return
        pendingAction = intent
        window.decorView.post { if (window.decorView.hasWindowFocus()) dispatchPendingAction() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.decorView.post { dispatchPendingAction() }
    }

    private fun dispatchPendingAction() {
        val intent = pendingAction ?: return
        pendingAction = null
        lifecycleScope.launch {
            vm.state.first { it.ready }
            when (intent.action?.substringAfterLast('.')) {
                "COMPILE" -> { vm.editRequest(intent.getStringExtra("request") ?: MockCompiler.template); vm.compile() }
                "DRY_RUN" -> vm.dryRun()
                "CONFIRM" -> vm.confirm()
                "REFRESH" -> {
                    vm.refresh(intent.getStringExtra("cardId"))
                    if (intent.getBooleanExtra("repeatStart", false)) vm.refresh(intent.getStringExtra("cardId"))
                }
                "CANCEL" -> vm.cancel()
                "DELETE" -> intent.getStringExtra("cardId")?.let(vm::delete)
                "DETAIL" -> vm.select(intent.getStringExtra("cardId"))
            }
        }
    }
}
