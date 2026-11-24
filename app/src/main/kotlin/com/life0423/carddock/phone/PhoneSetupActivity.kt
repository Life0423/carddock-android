package com.life0423.carddock.phone

import com.life0423.carddock.ui.ModelSettingsForm
import com.life0423.carddock.CardDockApplication
import com.life0423.carddock.ModelCheck
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.life0423.carddock.platform.CardDockAccessibilityService
import com.life0423.carddock.platform.mika.DockMikaHandoff
import com.life0423.carddock.ui.theme.CardDockTheme
import com.life0423.carddock.ui.theme.DockColors
import kotlinx.coroutines.launch

/** 手机端设置与恢复共用一个入口。助手连接成功以本包收到的 Binder 为准。 */
class PhoneSetupActivity : ComponentActivity() {
    private var tip by mutableStateOf("")
    private var showNotificationSettings by mutableStateOf(false)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) guidePairing() else {
            showNotificationSettings = true
            tip = "通知未获授权。可允许通知，或使用分屏配对。"
        }
    }

    // 导出可由测试者自行查看的连接与核验时间；不导出业务字段、配对码或模型配置。
    private val saveReport = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val state = (application as com.life0423.carddock.CardDockApplication).container.controller.state.value
            val result = state.cards.filter { it.rule.sourceId == com.life0423.carddock.platform.CAINIAO_PACKAGE }
                .mapNotNull { it.snapshot?.fetchedAt }.maxOrNull()
            val report = org.json.JSONObject().apply {
                put("记录时间", System.currentTimeMillis())
                put("应用标识", packageName)
                put("版本", packageManager.getPackageInfo(packageName, 0).versionName)
                put("机型", Build.MODEL)
                put("系统API", Build.VERSION.SDK_INT)
                put("系统启动计数", Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1))
                put("已配对", PhoneLink.prefs(this@PhoneSetupActivity).getBoolean("paired", false))
                val link = PhoneLink.prefs(this@PhoneSetupActivity)
                if (link.contains("pairedAt")) {
                    put("最近本机配对时间", link.getLong("pairedAt", 0))
                    put("配对端口自动发现", link.getBoolean("discoveredPairPort", false))
                }
                put("最近本机助手接入时间", PhoneLink.prefs(this@PhoneSetupActivity).getLong("connectedAt", 0))
                put("当前助手已连接", DockMikaHandoff.display.value != null)
                put("页面读取已连接", CardDockAccessibilityService.connected.value)
                put("已保存菜鸟卡最近成功快照时间", result ?: org.json.JSONObject.NULL)
            }
            tip = try {
                val out = contentResolver.openOutputStream(uri) ?: throw java.io.IOException()
                out.bufferedWriter().use { it.write(report.toString(2)) }
                "已保存本机验收记录。"
            } catch (_: java.io.IOException) { "保存失败，请选择可写的位置重试。" }
        }
    }

    private fun settings(action: String) {
        try {
            startActivity(Intent(action))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun guidePairing() {
        if (PairingReply.show(this)) {
            showNotificationSettings = false
            tip = "若系统直接返回，请先在「关于手机」启用开发者选项。"
            settings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        } else {
            showNotificationSettings = true
            tip = "「本机配对」通知未开启。也可以使用分屏配对。"
        }
    }

    override fun onResume() {
        super.onResume()
        if (showNotificationSettings && PairingReply.notificationsEnabled(this)) {
            showNotificationSettings = false
            tip = "通知已开启，可以开始本机配对。"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as CardDockApplication).container
        // 通知只负责限时配对。返回设置页或在分屏中配对成功后，由前台接续连接。
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                var firstResume = true
                PhoneLink.busy.collect { busy ->
                    val prefs = PhoneLink.prefs(this@PhoneSetupActivity)
                    if (!busy && prefs.getBoolean("paired", false) && (firstResume || prefs.getBoolean("connectOnReturn", false))) {
                        firstResume = false
                        if (DockMikaHandoff.display.value == null) PhoneLink.connect(this@PhoneSetupActivity, null)
                        // 切去系统设置会取消当前协程；回来后仍可接着连接。
                        if (!PhoneLink.busy.value) prefs.edit().remove("connectOnReturn").apply()
                    }
                }
            }
        }
        setContent {
            CardDockTheme {
                val tools by container.tools.collectAsStateWithLifecycle()
                var editModel by rememberSaveable { mutableStateOf(false) }
                val reader by CardDockAccessibilityService.connected.collectAsState()
                val helper by DockMikaHandoff.display.collectAsState()
                val message by PhoneLink.message.collectAsState()
                val busy by PhoneLink.busy.collectAsState()
                var manual by rememberSaveable { mutableStateOf(false) }
                var split by rememberSaveable { mutableStateOf(false) }
                var connectPort by rememberSaveable { mutableStateOf("") }
                var specifyPairPort by rememberSaveable { mutableStateOf(false) }
                var pairPort by remember { mutableStateOf("") }
                var code by remember { mutableStateOf("") }
                var confirmForget by remember { mutableStateOf(false) }
                val paired = PhoneLink.prefs(this).getBoolean("paired", false)
                Surface(Modifier.fillMaxSize(), color = DockColors.Background) {
                    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState())
                        .padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(onClick = { finish() }) { Text("返回") }
                        Text("手机运行环境", style = MaterialTheme.typography.headlineMedium)
                        val modelStatus = when {
                            tools.model != null -> tools.modelCheck.label
                            tools.modelBusy -> "读取中"
                            tools.modelCheck == ModelCheck.UNREADABLE -> tools.modelCheck.label
                            else -> "未配置"
                        }
                        Text("模型服务 · " + modelStatus, style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton({ editModel = true }) { Text("模型配置") }
                            if (tools.model != null) OutlinedButton(container::checkModel, enabled = !tools.modelBusy) {
                                Text("测试连接")
                            }
                        }
                        if (tools.modelCheck == ModelCheck.FAILED)
                            Text("请检查网络、服务地址、模型名和密钥，然后重新测试。")
                        HorizontalDivider()
                        Text("页面读取 · " + if (reader) "已连接" else "未开启")
                        if (!reader) OutlinedButton({ settings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }) { Text("打开页面读取设置") }
                        HorizontalDivider()
                        Text("后台助手 · " + when {
                            helper != null -> "已连接"
                            busy -> "处理中"
                            paired -> "已配对，待连接"
                            else -> "未配对"
                        }, style = MaterialTheme.typography.titleLarge)
                        if (Build.VERSION.SDK_INT < 34) {
                            Text("这部手机可使用卡片和公开数据。本机后台助手需要 Android 14 或更高版本。")
                        } else {
                            if (helper == null) {
                                Text(if (paired) "返回时会自动尝试连接。重启或换网后，请确认当前 Wi-Fi 已允许无线调试。"
                                    else "连接 Wi-Fi，在开发者选项中开启无线调试。选择「使用配对码配对设备」，保持系统窗口打开，下拉通知栏回复六位配对码。返回卡坞后会自动连接。")
                                if (paired) {
                                    Button(onClick = {
                                        tip = ""
                                        lifecycleScope.launch { PhoneLink.connect(this@PhoneSetupActivity, null) }
                                    }, enabled = !busy) { Text("连接助手") }
                                    TextButton({ settings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) }) { Text("打开开发者选项") }
                                    TextButton({ manual = !manual }) { Text(if (manual) "收起手动连接" else "手动连接") }
                                    if (manual) {
                                        Text("填写无线调试首页「IP 地址和端口」末尾的连接端口。")
                                        PortInput("连接端口", connectPort) { connectPort = it }
                                        OutlinedButton({
                                            val port = connectPort.toInt()
                                            lifecycleScope.launch { PhoneLink.connect(this@PhoneSetupActivity, port) }
                                        }, enabled = !busy && connectPort.toIntOrNull() in 1..65535) { Text("连接此端口") }
                                    }
                                }
                                OutlinedButton(onClick = {
                                    tip = ""
                                    if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) guidePairing()
                                    else notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }, enabled = !busy) { Text(if (paired) "重新配对" else "开始本机配对") }
                                Column {
                                    TextButton({ split = !split }) { Text(if (split) "收起分屏配对" else "使用分屏配对") }
                                    if (split) {
                                        Text("让卡坞与系统无线调试分屏显示，在配对窗口仍打开时填写。")
                                        TextButton({ specifyPairPort = !specifyPairPort }) {
                                            Text(if (specifyPairPort) "自动查找配对端口" else "手动指定配对端口")
                                        }
                                        if (specifyPairPort) PortInput("配对端口", pairPort) { pairPort = it }
                                        OutlinedTextField(code, { code = it }, label = { Text("六位配对码") },
                                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                                        val input = if (specifyPairPort && pairPort.isBlank()) null else
                                            PairingAnswer.parse(if (specifyPairPort) "$pairPort $code" else code)
                                        OutlinedButton({
                                            val answer = input ?: return@OutlinedButton
                                            code = ""
                                            lifecycleScope.launch { PhoneLink.pair(this@PhoneSetupActivity, answer.port, answer.code) }
                                        }, enabled = !busy && input != null) { Text("确认配对") }
                                    }
                                    TextButton({ settings(Settings.ACTION_DEVICE_INFO_SETTINGS) }) { Text("找不到开发者选项？打开关于手机") }
                                }
                            } else Text("现在可以返回空间，点卡片执行已授权的查询。手机重启后可在这里恢复连接。")
                            if (tip.isNotEmpty()) Text(tip, color = DockColors.Amber)
                            if (showNotificationSettings && helper == null) TextButton({
                                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                            }) { Text("打开通知设置") }
                            if (message.isNotEmpty()) Text(message)
                            OutlinedButton({ saveReport.launch("卡坞运行记录-${System.currentTimeMillis()}.json") }) { Text("保存验收记录") }
                            if (paired || helper != null) TextButton({ confirmForget = true }, enabled = !busy) { Text("停止助手并清除本机配对") }
                        }
                    }
                }
                if (editModel) AlertDialog(onDismissRequest = { editModel = false },
                    title = { Text("模型配置") },
                    text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                        ModelSettingsForm(tools, container::saveModel, container::clearModel)
                    } },
                    confirmButton = { TextButton({ editModel = false }) { Text("关闭") } })
                if (confirmForget) AlertDialog(onDismissRequest = { confirmForget = false }, title = { Text("清除本机配对？") },
                    text = { Text("后台查询会停止。个人卡片和模板会保留；下次连接需要重新配对。") },
                    confirmButton = { TextButton({
                        confirmForget = false
                        PairingReply.cancel(this)
                        lifecycleScope.launch { PhoneLink.forget(this@PhoneSetupActivity) }
                    }) { Text("清除配对") } },
                    dismissButton = { TextButton({ confirmForget = false }) { Text("保留") } })
            }
        }
    }
}

@Composable
private fun PortInput(label: String, port: String, onChange: (String) -> Unit) =
    OutlinedTextField(port, onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))

