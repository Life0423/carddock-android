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
import com.wangzan101.mika.experiment.MEITUAN_PACKAGE
import com.wangzan101.mika.experiment.JD_PACKAGE
import com.wangzan101.mika.experiment.JdProduct
import com.wangzan101.mika.experiment.JdShareEvidence
import com.wangzan101.mika.experiment.CAINIAO_MODEL_RULE
import com.wangzan101.mika.experiment.ruleJson
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
            var form by rememberSaveable { mutableStateOf(0) }
            var share by remember { mutableStateOf("") }
            var jdRefreshId by remember { mutableStateOf<String?>(null) }
            var jdPriceId by remember { mutableStateOf<String?>(null) }
            var refreshShare by remember { mutableStateOf("") }
            var shoppingNote by remember(s.shoppingTask?.savedAt) { mutableStateOf(s.shoppingTask?.note.orEmpty()) }
            var oid by remember { mutableStateOf("") }
            var orderNumber by remember { mutableStateOf("") }
            val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                        Text("功能重组器", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(20.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            listOf("空间", "模板", "选购", "我的").forEachIndexed { i, name ->
                                TextButton(onClick = { tab = i; vm.select(null) }, enabled = !s.busy) { Text(if (tab == i) "● $name" else name) }
                            }
                        }
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (tab == 0) {
                                val detail = s.cards.find { it.id == s.selected }
                                if (detail != null) {
                                    TextButton(onClick = { vm.select(null) }, enabled = !s.busy) { Text("返回空间") }
                                    Text(detail.title, style = MaterialTheme.typography.titleLarge)
                                    Text("来源：${detail.source}\n规则：${detail.ruleId} v${detail.ruleVersion}\n卡片 ID：${detail.id}")
                                    Text("上次成功结果：${detail.lastValidText}\n核验时间：${time.format(Date(detail.lastSuccessAt))}\n服务器更新时间未知")
                                    Text("最近尝试：${time.format(Date(detail.lastAttemptAt))}\n${detail.reason}")
                                    if (JdProduct.forRule(detail.ruleId) != null) {
                                        TextButton(onClick = { refreshShare = ""; jdRefreshId = detail.id }, enabled = !s.busy) {
                                            Text("用分享链接对照")
                                        }
                                        TextButton(onClick = { jdPriceId = detail.id }, enabled = !s.busy) {
                                            Text("核验京东 App 展示价")
                                        }
                                    }
                                    if (detail.ruleId in setOf("cainiao-pickup-empty", CAINIAO_MODEL_RULE)) {
                                        TextButton(onClick = { vm.prepareCainiaoReplacement(detail, rollback = false) }, enabled = !s.busy) {
                                            Text(if (detail.ruleId == CAINIAO_MODEL_RULE) "试用模板规则" else "试用已保存的模型规则")
                                        }
                                        if (detail.previousRuleId != null) {
                                            TextButton(onClick = { vm.prepareCainiaoReplacement(detail, rollback = true) }, enabled = !s.busy) {
                                                Text("试运行上次规则")
                                            }
                                        }
                                        if (s.replacementCardId == detail.id) {
                                            Text(s.candidateJson, style = MaterialTheme.typography.bodySmall)
                                            Button(onClick = { vm.dryRun() }, enabled = !s.busy) { Text("试运行候选规则") }
                                            Button(onClick = { vm.confirm() }, enabled = s.canConfirm && !s.busy) { Text("确认更新规则") }
                                        }
                                    }
                                    Text("运行记录", style = MaterialTheme.typography.titleMedium)
                                    s.history.forEach { Text("${time.format(Date(it.finishedAt))} · ${it.outcome}\n${it.elapsedMs} ms · 模型请求增量 ${it.modelCallsDelta}\n${it.message}") }
                                } else {
                                    Text("我的日常空间", style = MaterialTheme.typography.titleLarge)
                                    if (s.cards.isEmpty()) Text("还没有卡片。到模板页匹配需求，通过试运行后确认创建。")
                                    Button(onClick = { vm.refresh() }, enabled = s.ready && !s.busy && s.cards.isNotEmpty()) {
                                        Text("顺序刷新全部")
                                    }
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
                                                    Button(onClick = { vm.refresh(card.id) }, enabled = !s.busy) {
                                                        Text(if (card.status == CardStatus.NEED_HANDOFF) "继续核验" else "刷新")
                                                    }
                                                    TextButton(onClick = { vm.select(card.id) }) { Text("详情") }
                                                    TextButton(onClick = { deleting = card }, enabled = !s.busy) { Text("删除") }
                                                }
                                            }
                                        }
                                    }
                                }
                            } else if (tab == 1) {
                                Text("新建查询卡片", style = MaterialTheme.typography.titleLarge)
                                Row {
                                    TextButton(onClick = { form = 0; share = ""; vm.editRequest(s.request) },
                                        enabled = form != 0 && !s.busy) { Text("菜鸟待取") }
                                    TextButton(onClick = { form = 1; share = ""; vm.editRequest(s.request) },
                                        enabled = form != 1 && !s.busy) { Text("美团历史订单") }
                                }
                                JdProduct.entries.forEachIndexed { index, product ->
                                    TextButton(onClick = { form = index + 2; share = ""; vm.compileJdShare(product) },
                                        enabled = form != index + 2 && s.ready && !s.busy) { Text(product.cardTitle) }
                                }
                                if (form >= 2) {
                                    Text("可直接核验京东公开页。填写分享链接可额外对照商品身份。")
                                    OutlinedTextField(share, { share = it; vm.editJdShare() },
                                        label = { Text("京东分享链接（可选）") }, enabled = !s.busy, modifier = Modifier.fillMaxWidth())
                                    if (s.candidateJson.isEmpty()) {
                                        Button(onClick = { vm.compileJdShare(JdProduct.entries[form - 2]) },
                                            enabled = s.ready && !s.busy) { Text("载入京东核验规则") }
                                    }
                                } else if (form == 1) {
                                    OutlinedTextField(oid, { oid = it; vm.editRequest(s.request) }, label = { Text("订单路由 oid") },
                                        enabled = !s.busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(orderNumber, { orderNumber = it; vm.editRequest(s.request) }, label = { Text("完整订单号码") },
                                        enabled = !s.busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    Button(onClick = { vm.compileOrder(oid, orderNumber) },
                                        enabled = s.ready && !s.busy && oid.isNotBlank() && orderNumber.isNotBlank()) {
                                        Text("生成美团候选规则")
                                    }
                                } else {
                                    OutlinedTextField(value = s.request, onValueChange = vm::editRequest, enabled = !s.busy,
                                        label = { Text("你的需求") }, modifier = Modifier.fillMaxWidth())
                                    TextButton(onClick = { vm.editRequest(MockCompiler.template) }, enabled = !s.busy) {
                                        Text("使用内置需求：查看菜鸟待取包裹")
                                    }
                                    Button(onClick = vm::compile, enabled = s.ready && !s.busy) { Text("匹配模板，生成候选规则") }
                                    OutlinedButton(onClick = vm::compileCainiaoWithModel, enabled = s.ready && !s.busy) {
                                        Text("用主模型生成菜鸟候选")
                                    }
                                }
                                if (s.candidateJson.isNotEmpty()) {
                                    Text(when (s.candidateSource) {
                                        JD_PACKAGE -> "京东 · 公开商品页身份核验"
                                        MEITUAN_PACKAGE -> "美团 12.68.402 · 重开订单并核验状态"
                                        else -> "菜鸟 8.11.1105 · 首页 → 取包裹 → 核验标题与空状态"
                                    })
                                    TextButton(onClick = { preview = !preview }) { Text(if (preview) "收起规则" else "查看候选 JSON") }
                                    if (preview) Text(s.candidateJson, style = MaterialTheme.typography.bodySmall)
                                    Button(onClick = { vm.dryRun(jdShare = share.takeIf { form >= 2 && it.isNotBlank() }) },
                                        enabled = !s.busy) { Text("真实试运行") }
                                    Button(onClick = { vm.confirm(); oid = ""; orderNumber = ""; share = ""; tab = 0 },
                                        enabled = s.canConfirm && !s.busy) { Text("结果正确，确认创建卡片") }
                                }
                            } else if (tab == 2) {
                                Text("京东 A/B 对比", style = MaterialTheme.typography.titleLarge)
                                val products = JdProduct.entries.map { product ->
                                    s.cards.filter { it.ruleId == product.ruleId }.maxByOrNull { it.lastSuccessAt }
                                }
                                JdProduct.entries.zip(products).forEach { (product, card) ->
                                    Text(product.cardTitle, style = MaterialTheme.typography.titleMedium)
                                    Text(card?.lastValidText ?: "请先从模板创建商品卡片")
                                    if (card != null) {
                                        Text("上次核验：${time.format(Date(card.lastSuccessAt))}")
                                        if (card.status != CardStatus.FRESH_VALID) Text(card.reason)
                                        TextButton(onClick = { vm.refresh(card.id) }, enabled = !s.busy) { Text("刷新${product.cardTitle}") }
                                    }
                                }
                                val prices = products.map { card ->
                                    card?.snapshotJson?.let { ruleJson.decodeFromString<JdShareEvidence>(it).appPrice }
                                }
                                val a = prices[0]
                                val b = prices[1]
                                Text(when {
                                    a == null || b == null -> "价格未核验，无法比较到手价。"
                                    a.visibleDiscount == null || b.visibleDiscount == null ||
                                        a.visibleDiscount != b.visibleDiscount || a.priceLabel != b.priceLabel ->
                                        "两次 App 核验的价格条件不同或未知，无法直接比较到手价。"
                                    else -> "两次 App 核验显示相同价格标签和优惠条件。请按各自核验时间查看。"
                                })
                                OutlinedTextField(shoppingNote, { shoppingNote = it }, label = { Text("选购备注") }, enabled = !s.busy)
                                Button(onClick = { vm.saveShoppingTask(shoppingNote) },
                                    enabled = !s.busy && products.all { it?.status == CardStatus.FRESH_VALID }) { Text("保存 A/B 选购任务") }
                                s.shoppingTask?.let { task ->
                                    Text("已保存的选购任务", style = MaterialTheme.typography.titleMedium)
                                    listOf(task.productA, task.productB).forEach { snapshot ->
                                        val product = ruleJson.decodeFromString<JdShareEvidence>(snapshot)
                                        Text("${product.title}\nSKU ${product.sku} · ${product.variant}")
                                    }
                                    if (task.note.isNotBlank()) Text(task.note)
                                    Text("保存时间：${time.format(Date(task.savedAt))}")
                                    task.lastCheckedAt?.let { Text("重新核验：${time.format(Date(it))}\n${task.lastCheck}") }
                                    Button(onClick = vm::recheckShoppingTask, enabled = !s.busy) { Text("重新核验选购任务") }
                                }
                            } else {
                                Text("关于日常空间", style = MaterialTheme.typography.titleLarge)
                                Text("规则、卡片和运行记录通过 Room 保存在本机。普通刷新读取已验证规则，不需要配置模型。")
                                Text("请在手机空闲时启动查询。切换到其他应用后，查询不会在那里点击，也不会自动覆盖该应用。")
                                TextButton(onClick = { startActivity(Intent(this@WorkspaceActivity, com.wangzan101.mika.experiment.CainiaoExperimentActivity::class.java)) }, enabled = !s.busy) { Text("打开规则实验工具") }
                            }
                            HorizontalDivider()
                            Text(s.message)
                            if (s.busy) OutlinedButton(onClick = vm::cancel) { Text("取消查询") }
                            Text("${s.outcome} · 步骤 ${s.step}\n最近查询 ${s.elapsedMs} ms · 模型请求增量 ${s.modelCallsDelta}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    jdPriceId?.let { id ->
                        AlertDialog(onDismissRequest = { jdPriceId = null },
                            title = { Text("核验京东 App 展示价") },
                            text = { Text("点击开始后，30 秒内切到京东，打开本卡对应的商品和规格。识别到详情后会读取展示价，并复制公开商品链接来确认 SKU。") },
                            confirmButton = {
                                TextButton(onClick = { vm.refreshJdAppPrice(id); jdPriceId = null }, enabled = !s.busy) {
                                    Text("开始核验")
                                }
                            },
                            dismissButton = { TextButton(onClick = { jdPriceId = null }) { Text("取消") } })
                    }
                    jdRefreshId?.let { id ->
                        AlertDialog(onDismissRequest = { jdRefreshId = null; refreshShare = "" },
                            title = { Text("用京东分享链接对照") },
                            text = {
                                OutlinedTextField(refreshShare, { refreshShare = it },
                                    label = { Text("从商品详情复制的分享链接") })
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    vm.refreshJdShare(id, refreshShare)
                                    jdRefreshId = null
                                    refreshShare = ""
                                }, enabled = refreshShare.isNotBlank() && !s.busy) { Text("核验") }
                            },
                            dismissButton = { TextButton(onClick = { jdRefreshId = null; refreshShare = "" }) { Text("取消") } })
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

    override fun onResume() {
        super.onResume()
        vm.attachWorkspaceTask(taskId)
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
                "DRY_RUN" -> vm.dryRun(cainiaoOnly = true)
                "CONFIRM" -> vm.confirm(cainiaoOnly = true)
                "REFRESH" -> {
                    vm.refresh(intent.getStringExtra("cardId"), cainiaoOnly = true)
                    if (intent.getBooleanExtra("repeatStart", false)) vm.refresh(intent.getStringExtra("cardId"), cainiaoOnly = true)
                }
                "CANCEL" -> vm.cancel()
                "DELETE" -> intent.getStringExtra("cardId")?.let(vm::delete)
                "DETAIL" -> vm.select(intent.getStringExtra("cardId"))
            }
        }
    }
}
