package com.life0423.carddock.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.material3.OutlinedButton
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
    var opening by remember { mutableStateOf(false) }
    OutlinedButton(enabled = enabled && !opening, onClick = {
        val reader = CardDockAccessibilityService.instance
        val home = Intent(Intent.ACTION_VIEW, Uri.parse("guoguo://go/home_page")).setPackage(CAINIAO_PACKAGE)
        if (reader == null || home.resolveActivity(context.packageManager) == null) {
            Toast.makeText(context, if (reader == null) "请先启用卡坞页面读取服务" else "本机未找到菜鸟", Toast.LENGTH_SHORT).show()
            return@OutlinedButton
        }
        opening = true
        scope.launch {
            try {
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
                if (result is DeviceResponse.NeedsUser) Toast.makeText(context,result.message,Toast.LENGTH_LONG).show()
            } catch (_: SecurityException) {
                Toast.makeText(context,"菜鸟当前未允许从卡坞打开首页，请在菜鸟查看身份码。",Toast.LENGTH_LONG).show()
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context,"系统未找到菜鸟入口",Toast.LENGTH_SHORT).show()
            } finally { opening = false; onFinished() }
        }
    }) { Text(if (opening) "正在打开…" else "出示取件码") }
}

/** 旧卡未绑定亮码动作。让用户明确选择从哪份已核验模板打开官方身份码。 */
@Composable
internal fun PickupTemplateShortcut(template: com.life0423.carddock.core.model.TemplateDefinition, enabled: Boolean) {
    val entry = template.pickupEntry ?: return
    var selecting by remember(template.id, template.revision) { mutableStateOf(false) }
    OutlinedButton({ selecting = true }, enabled = enabled) { Text("出示取件码") }
    if (selecting) androidx.compose.material3.AlertDialog(
        onDismissRequest = { selecting = false },
        title = { Text("使用「${template.title}」亮码") },
        text = { Text("通过这份已核验的个人模板，打开菜鸟官方取件身份码。") },
        confirmButton = { PickupCodeButton(entry, enabled) { selecting = false } },
        dismissButton = { androidx.compose.material3.TextButton({ selecting = false }) { Text("取消") } },
    )
}
