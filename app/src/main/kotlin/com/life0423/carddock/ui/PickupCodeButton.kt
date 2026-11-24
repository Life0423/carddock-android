package com.life0423.carddock.ui

import com.life0423.carddock.navigationIssue
import com.life0423.carddock.core.model.PhoneCapability

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.life0423.carddock.core.model.PickupEntry
import com.life0423.carddock.core.port.DeviceResponse
import com.life0423.carddock.platform.CAINIAO_PACKAGE
import com.life0423.carddock.platform.CardDockAccessibilityService
import com.life0423.carddock.platform.inspectPickupCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 只在用户点此按钮时打开来源应用。复用已验入口，不更新查询快照或核验时间。 */
@Composable
internal fun PickupCodeButton(entry: PickupEntry, enabled: Boolean, onFinished: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var missing by remember { mutableStateOf<PhoneCapability?>(null) }
    var opening by remember { mutableStateOf(false) }
    Button(enabled = enabled && !opening, onClick = {
        missing = null
        val reader = CardDockAccessibilityService.instance
        val home = Intent(Intent.ACTION_VIEW, Uri.parse("guoguo://go/home_page")).setPackage(CAINIAO_PACKAGE)
        if (reader == null || home.resolveActivity(context.packageManager) == null) {
            if (reader == null) missing = PhoneCapability.PAGE_READER
            Toast.makeText(context, if (reader == null) "请先启用卡坞页面读取服务" else "本机未找到菜鸟", Toast.LENGTH_SHORT).show()
            return@Button
        }
        opening = true
        scope.launch {
            try {
                val app = context.applicationContext as com.life0423.carddock.CardDockApplication
                val issue = app.container.runNavigation {
                    context.startActivity(home)
                    val result = withContext(Dispatchers.IO) {
                        val started = withTimeoutOrNull(6000) {
                            while (reader.observe(pickup=true)?.packageName != CAINIAO_PACKAGE) delay(180)
                            true
                        } == true
                        if (!started) DeviceResponse.NeedsUser("未能打开菜鸟，请回到卡坞重试。")
                        else inspectPickupCode(entry,{ reader.observe(pickup=true) },reader::clickPickupEntry,
                            { reader === CardDockAccessibilityService.instance }, acceptAlreadyOpen = true)
                    }
                    result.navigationIssue()
                }
                issue?.let { missing = it.missingCapability; Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
            } catch (_: SecurityException) {
                Toast.makeText(context,"菜鸟当前未允许从卡坞打开首页，请在菜鸟查看身份码。",Toast.LENGTH_LONG).show()
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context,"系统未找到菜鸟入口",Toast.LENGTH_SHORT).show()
            } finally { opening = false; onFinished() }
        }
    }) { Text(if (opening) "正在打开…" else "出示取件码") }
    PhoneRecoveryButton(missing)
}

/** 列表交接独立于点卡刷新。来源未就绪时在当前界面保留重试入口。 */
@Composable
internal fun PickupListActions(enabled: Boolean, codeEntry: PickupEntry?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preparing by remember { mutableStateOf(false) }
    var issue by remember { mutableStateOf<DeviceResponse.NeedsUser?>(null) }
    Button(enabled = enabled && !preparing, onClick = {
        preparing = true
        issue = null
        scope.launch {
            try {
                val app = context.applicationContext as com.life0423.carddock.CardDockApplication
                issue = app.container.openPickupList()
            } finally { preparing = false }
        }
    }) { Text(if (preparing) "正在准备待取列表…" else "打开菜鸟待取列表") }
    // 已在本机核验的亮码入口可与列表并列，两次操作均由用户独立触发。
    codeEntry?.let { PickupCodeButton(it, enabled && !preparing) }
    issue?.let { Text(it.message); PhoneRecoveryButton(it.missingCapability) }
}

/** 旧卡未绑定亮码动作。让用户明确选择从哪份已核验模板打开官方身份码。 */
@Composable
internal fun PickupTemplateShortcut(template: com.life0423.carddock.core.model.TemplateDefinition, enabled: Boolean) {
    val entry = template.pickupEntry ?: return
    var selecting by remember(template.id, template.revision) { mutableStateOf(false) }
    Button({ selecting = true }, enabled = enabled) { Text("出示取件码") }
    if (selecting) androidx.compose.material3.AlertDialog(
        onDismissRequest = { selecting = false },
        title = { Text("使用「${template.title}」亮码") },
        text = { Text("通过这份已核验的个人模板，打开菜鸟官方取件身份码。") },
        confirmButton = { PickupCodeButton(entry, enabled) { selecting = false } },
        dismissButton = { androidx.compose.material3.TextButton({ selecting = false }) { Text("取消") } },
    )
}
