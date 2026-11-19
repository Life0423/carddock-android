package com.life0423.carddock.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import com.life0423.carddock.data.SquareStore
import com.life0423.carddock.data.SquareState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.life0423.carddock.PrototypeTools
import com.life0423.carddock.core.session.DemoScenario
import com.life0423.carddock.core.session.PrototypeActions
import com.life0423.carddock.ui.theme.DockColors

/** 配置常态只显示摘要，操作通过对应行进入表单或说明。 */
@Composable
internal fun ProfileScreen(
    scenario: DemoScenario, actions: PrototypeActions, openAccessibilitySettings: () -> Unit,
    tools: PrototypeTools?, saveModel: (String, String, String) -> Unit,
    clearModel: () -> Unit, pageReaderConnected: Boolean, mikaConnected: Boolean,
    store: SquareStore? = null, account: SquareState = SquareState(), requestLogin: Boolean = false,
    onAccountClosed: () -> Unit = {},
) {
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(requestLogin) { if (requestLogin && store != null) dialog = "登录" }
    val closeAccount = { dialog = null; onAccountClosed() }
    var showDemo by rememberSaveable { mutableStateOf(false) }
    if (store != null && dialog in listOf("登录", "修改密码")) {
        androidx.activity.compose.BackHandler { if (!account.busy) closeAccount() }
        AccountForm(store, account, dialog == "修改密码", closeAccount)
        return
    }
    LazyColumn(
        modifier = Modifier.testTag("profile_list"),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (store != null && account.available) item {
            ProfileGroup("我的账号") {
                ProfileRow(account.account ?: "登录或注册", Icons.Rounded.Person,
                    if (account.signedIn) "账号管理" else null) {
                    dialog = if (account.signedIn) "账号管理" else "登录"
                }
                if (!account.signedIn && account.message == SquareStore.OFFLINE_LOGOUT)
                    Text(SquareStore.OFFLINE_LOGOUT, Modifier.padding(12.dp), color = DockColors.Muted)
                if (account.signedIn) {
                    HorizontalDivider(color = DockColors.Outline)
                    ProfileRow("我的发布", DockIcons.GridView) { dialog = "我的发布"; store.loadPublications() }
                }
            }
        }
        item {
            ProfileGroup("手机能力") {
                ProfileRow("页面读取服务", Icons.Rounded.Phone, if (pageReaderConnected) "已连接" else "未连接",
                    statusConnected = pageReaderConnected)
                HorizontalDivider(color = DockColors.Outline)
                ProfileRow("无障碍设置", Icons.Rounded.Settings, modifier = Modifier.testTag("accessibility_settings"),
                    onClick = openAccessibilitySettings)
                HorizontalDivider(color = DockColors.Outline)
                ProfileRow("Mika 后台助手", Icons.Rounded.Build, if (mikaConnected) "已连接" else "未连接",
                    statusConnected = mikaConnected) { dialog = "Mika 后台助手" }
            }
        }
        if (tools != null) item {
            ProfileGroup("模型服务") {
                ProfileRow("模型配置", DockIcons.AutoAwesomeMosaic, tools.model?.model ?: if (tools.modelBusy) "读取中" else "未配置") {
                    dialog = "模型配置"
                }
                Text("用于生成菜鸟候选", Modifier.padding(start = 14.dp, bottom = 12.dp),
                    color = DockColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            ProfileGroup("本地数据") {
                ProfileRow("数据管理", DockIcons.Inventory2) { dialog = "数据管理" }
                HorizontalDivider(color = DockColors.Outline)
                ProfileRow("开发与演示", Icons.Rounded.Build, if (showDemo) "收起" else null,
                    modifier = Modifier.testTag("toggle_demo")) { showDemo = !showDemo }
            }
        }
        if (showDemo) item {
            ProfileGroup("演示场景") {
                Column(Modifier.selectableGroup()) {
                    DemoScenario.entries.forEachIndexed { index, item ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .selectable(scenario == item, role = Role.RadioButton) { actions.changeScenario(item) }
                                .testTag("scenario_${item.name}")
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(when (item) {
                                DemoScenario.NORMAL -> "正常获取"
                                DemoScenario.NEEDS_USER -> "需要接管"
                                DemoScenario.READ_FAILURE -> "读取失败"
                                DemoScenario.WRONG_TARGET -> "对象不匹配"
                            }, Modifier.weight(1f))
                            RadioButton(selected = scenario == item, onClick = null)
                        }
                        if (index < DemoScenario.entries.lastIndex) HorizontalDivider(color = DockColors.Outline)
                    }
                }
            }
        }
        item {
            ProfileGroup("帮助与关于") {
                ProfileRow("支持范围", DockIcons.GridView) { dialog = "支持范围" }
                HorizontalDivider(color = DockColors.Outline)
                ProfileRow("隐私与开源", Icons.Rounded.Lock) { dialog = "隐私与开源" }
                HorizontalDivider(color = DockColors.Outline)
                ProfileRow("关于卡坞", Icons.Rounded.Info, "原型版") { dialog = "关于卡坞" }
            }
        }
    }

    when (dialog) {
        "账号管理" -> if (store != null) {
            LaunchedEffect(account.signedIn) { if (!account.signedIn) closeAccount() }
            AlertDialog(onDismissRequest = closeAccount, title = { Text(account.account.orEmpty()) },
                text = { Column {
                    TextButton({ dialog = "修改密码" }, enabled = !account.busy) { Text("修改密码") }
                    TextButton(store::signOut, enabled = !account.busy) { Text("退出登录") }
                    account.message?.let { Text(it) }
                } }, confirmButton = { TextButton(closeAccount) { Text("关闭") } })
        }
        "我的发布" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("我的发布") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    account.busy -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    account.message != null -> Text(account.message)
                    account.publications.isEmpty() -> Text("暂无已发布模板")
                }
                account.publications.forEach { item -> Text("${item.title} · 第 ${item.version} 版 · ${item.downloads} 次下载") }
            } }, confirmButton = { TextButton({ dialog = null }) { Text("关闭") } })
        "模型配置" -> if (tools != null) AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("模型配置") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ModelSettingsForm(tools, saveModel, clearModel)
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("关闭") } },
        )
        "数据管理" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("数据管理") },
            text = { Text("卡片和选购快照保存在本机。可恢复初始演示卡片。") },
            confirmButton = {
                TextButton(onClick = { dialog = "重置演示内容" }, modifier = Modifier.testTag("reset_demo")) {
                    Text("重置演示内容")
                }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("关闭") } },
        )
        "重置演示内容" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("恢复初始演示内容？") },
            text = { Text("这会清除你在原型中添加的卡片，并停止当前运行。") },
            confirmButton = {
                TextButton(
                    onClick = { dialog = null; actions.resetDemo() },
                    modifier = Modifier.testTag("confirm_reset"),
                ) { Text("重置") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("保留") } },
        )
        "支持范围", "隐私与开源", "关于卡坞", "Mika 后台助手" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(checkNotNull(dialog)) },
            text = {
                Text(when (dialog) {
                    "Mika 后台助手" -> "调试版通过 USB 启动内置助手。连接电脑，在工程根目录运行 tools/start-mika-helper.ps1。卡坞进程退出或手机重启后需要重新连接。"
                    "支持范围" -> "支持菜鸟明确待取空状态、美团已完成订单和京东固定 A/B 商品。启用页面读取后，按核验提示切换到对应 App。"
                    "隐私与开源" -> "第三方页面仅在主动核验时读取。订单号码和模型密钥保存在本机。模型仅收到固定菜鸟需求。卡坞采用 Apache 2.0 许可证。"
                    else -> "卡坞 CardDock · Android 原型"
                })
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("关闭") } },
        )
    }
}

/** 注册、登录、改密共用账号入口。口令仅在当前表单存活，服务端返回成功后关闭。 */
@Composable
private fun AccountForm(store: SquareStore, state: SquareState, change: Boolean, close: () -> Unit) {
    var register by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var current by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    val title = if (change) "修改密码" else if (register) "注册账号" else "登录"
    LaunchedEffect(state.busy, submitted) {
        if (submitted && !state.busy && (if (change) state.message == "密码已更新" else state.signedIn)) close()
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (!change) OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("用户名") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
        if (change) OutlinedTextField(current, { current = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("当前密码") }, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(if (change) "新密码" else "密码") },
            supportingText = if (register || change) ({ Text("8 至 128 个字符") }) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = PasswordVisualTransformation())
        if (!change) TextButton({ register = !register; submitted = false; password = "" }, enabled = !state.busy) {
            Text(if (register) "已有账号，去登录" else "创建账号")
        }
        if (submitted) state.message?.let { Text(it, color = DockColors.Muted) }
        DockPrimaryAction(if (change) "保存" else title, onClick = {
            submitted = true
            if (change) store.changePassword(current, password) else store.signIn(name, password, register)
        }, enabled = !state.busy && (if (change) current.isNotEmpty() else name.isNotBlank()) && password.isNotEmpty())
        TextButton(close, enabled = !state.busy) { Text("取消") }
    }
}

/** 一组设置共享同一个背景，标题独立于可点击项。 */
@Composable
private fun ProfileGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Surface(color = DockColors.Surface, shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, DockColors.Outline)) {
            Column(content = content)
        }
    }
}

/** 只读状态行不显示箭头，其余行打开对应设置。 */
@Composable
private fun ProfileRow(
    label: String, icon: ImageVector, value: String? = null, modifier: Modifier = Modifier,
    statusConnected: Boolean = false, onClick: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick, role = Role.Button) else Modifier)
            .heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = DockColors.Muted)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (value != null) Text(value, color = if (statusConnected) DockColors.Mint else DockColors.Muted,
            style = MaterialTheme.typography.bodySmall)
        if (onClick != null) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null,
            Modifier.size(18.dp), tint = DockColors.Muted)
    }
}
