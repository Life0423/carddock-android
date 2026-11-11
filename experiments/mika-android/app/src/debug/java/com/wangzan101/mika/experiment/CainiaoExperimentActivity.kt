package com.wangzan101.mika.experiment

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CainiaoExperimentActivity : ComponentActivity() {
    private val vm: CainiaoExperimentViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by vm.state.collectAsState()
            var editing by rememberSaveable { mutableStateOf(false) }
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)
                            .verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("实验 2 · 规则试运行", style = MaterialTheme.typography.headlineSmall)
                        Text("候选规则会真实打开并操作菜鸟。先试运行，核对结果后再确认保存；无需配置模型。")
                        OutlinedButton(onClick = vm::loadTemplate, enabled = !state.running) { Text("加载菜鸟内置模板") }
                        Text(state.summary)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = vm::dryRun, enabled = state.candidateReady && !state.running) { Text("试运行候选规则") }
                            OutlinedButton(onClick = vm::cancel, enabled = state.running && state.mode != "SAVE") { Text("取消") }
                        }
                        Button(onClick = vm::confirm, enabled = state.canConfirm && !state.running) { Text("结果正确，确认保存") }
                        Text("已验证规则：${state.savedRule.ifEmpty { "尚未保存" }}")
                        OutlinedButton(onClick = vm::runSaved, enabled = state.savedRule.isNotEmpty() && !state.running) { Text("从文件重载并执行已验证规则") }
                        Text("阶段：${state.stepIndex} · ${state.stage}\n结果：${state.outcome}")
                        Text(state.message, style = MaterialTheme.typography.titleMedium)
                        if (state.startedAt != 0L) {
                            val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                            Text("本次操作：${format.format(Date(state.startedAt))}")
                            if (!state.running && state.ledgerCallsDelta != null) {
                                Text("来源：${state.mode} · 耗时 ${state.elapsedMs} ms\n模型请求账本增量：${state.ledgerCallsDelta}\n已配置主模型：${state.modelConfigured}")
                            }
                            if (state.stepIndex > 0) Text("导航点击：${state.navigationClicks} · 节点数：${state.nodesRead}\n规则：${state.ruleId} v${state.ruleVersion}")
                        }
                        TextButton(onClick = { editing = !editing }, enabled = !state.running) { Text(if (editing) "收起候选 JSON" else "编辑候选 JSON") }
                        if (editing) {
                            OutlinedTextField(value = state.draft, onValueChange = vm::editDraft, enabled = !state.running,
                                label = { Text("候选规则 JSON；修改后需重新试运行") },
                                modifier = Modifier.fillMaxWidth(), minLines = 6, maxLines = 12)
                        }
                        Text("本轮只核验明确空状态。有包裹时不提取取件码。时间为页面核验时间，服务器数据更新时间未知。")
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

    /** Debug automation calls the exact methods used by the visible controls. */
    private fun acceptDebugAction(intent: Intent) {
        when (intent.action) {
            "com.wangzan101.mika.experiment.RUN",
            "com.wangzan101.mika.experiment.RULE_DRY_RUN" -> {
                vm.dryRun()
                if (intent.getBooleanExtra("repeatStart", false)) vm.dryRun()
            }
            "com.wangzan101.mika.experiment.CANCEL" -> vm.cancel()
            "com.wangzan101.mika.experiment.RULE_LOAD_TEMPLATE" -> vm.loadTemplate()
            "com.wangzan101.mika.experiment.RULE_LOAD_JSON" -> vm.loadIncoming()
            "com.wangzan101.mika.experiment.RULE_CONFIRM" -> vm.confirm()
            "com.wangzan101.mika.experiment.RULE_RUN_SAVED" -> vm.runSaved()
        }
    }
}
