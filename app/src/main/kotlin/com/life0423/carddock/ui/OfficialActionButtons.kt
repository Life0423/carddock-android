package com.life0423.carddock.ui

import com.life0423.carddock.launchSavedApp
import com.life0423.carddock.launchableApps
import com.life0423.carddock.resolveSaved
import android.content.ActivityNotFoundException
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import com.life0423.carddock.core.port.DeviceResponse
import com.life0423.carddock.core.rule.RuleSpec
import android.widget.Toast
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.life0423.carddock.ai.MCD_PACKAGE
import com.life0423.carddock.core.model.PickupEntry
import com.life0423.carddock.core.rule.OfficialAction

/** 新的动作配置与旧卡共享按钮；预览、空间和卡片详情使用相同的名称和目标。 */
@Composable
internal fun OfficialActionButtons(rule: RuleSpec, list: Boolean, entry: PickupEntry?,
    listCode: PickupEntry?, enabled: Boolean, fallback: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    when (rule.officialAction ?: if (list) OfficialAction.PICKUP_LIST else if (entry != null) OfficialAction.PICKUP_CODE else null) {
        OfficialAction.OPEN_APP -> {
            val app = remember(rule.targetKey) {
                context.launchableApps(rule.targetKey.substringBefore('/')).resolveSaved(rule.targetKey)
            }
            Button(enabled = enabled, onClick = { result = context.launchSavedApp(rule.targetKey).orEmpty() }) {
                Text("打开")
                if (app != null) AppEntryLabel(app, false) else Text("应用")
            }
            if (result.isNotBlank()) Text(result)
        }
        OfficialAction.PICKUP_LIST -> PickupListActions(enabled, entry ?: listCode)
        OfficialAction.PICKUP_CODE -> entry?.let { PickupCodeButton(it, enabled) }
        OfficialAction.REPEAT_MEAL -> rule.meal?.takeIf { it.store != null && it.choices.size == 2 }?.let { meal ->
            var issue by remember(meal) { mutableStateOf<DeviceResponse.NeedsUser?>(null) }
            Button(enabled = enabled && !checking, onClick = {
                checking = true
                issue = null
                scope.launch {
                    try {
                        val app = context.applicationContext as com.life0423.carddock.CardDockApplication
                        issue = app.container.openFixedMeal(meal)
                    } finally { checking = false }
                }
            }) { Text(if (checking) "正在核对门店和搭配…" else "打开购买") }
            issue?.let { Text(it.message); PhoneRecoveryButton(it.missingCapability) }
        }
        OfficialAction.ADD_MEAL -> rule.meal?.takeIf { it.store != null && it.choices.isNotEmpty() }?.let { meal ->
            var pending by remember { mutableStateOf<kotlinx.coroutines.CompletableDeferred<Boolean>?>(null) }
            var price by remember { mutableStateOf("") }
            Button(enabled = enabled && !checking, onClick = {
                checking = true
                result = ""
                scope.launch {
                    try {
                        val app = context.applicationContext as com.life0423.carddock.CardDockApplication
                        val issue = app.container.addMeal(meal) { quote ->
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                                val answer = kotlinx.coroutines.CompletableDeferred<Boolean>()
                                price = quote
                                pending = answer
                                try { answer.await() } finally { pending = null }
                            }
                        }
                        result = issue?.message ?: "已核对官方购物车，新增 1 份。"
                    } finally { checking = false }
                }
            }) { Text(if (checking) "正在核对加购…" else "准备加入购物车") }
            pending?.let { answer ->
                androidx.compose.material3.AlertDialog(onDismissRequest = { answer.complete(false) },
                    title = { Text("确认加入购物车？") },
                    text = { Text("${meal.store}\n${meal.product}\n" + meal.choices.entries.joinToString("\n") { (g,n) -> "$g · $n" } +
                        "\n1 份 · 官方当前价 ¥$price") },
                    confirmButton = { androidx.compose.material3.TextButton({ answer.complete(true) }) { Text("加入购物车 1 份") } },
                    dismissButton = { androidx.compose.material3.TextButton({ answer.complete(false) }) { Text("取消") } })
            }
            if (result.isNotBlank()) Text(result)
        }
        OfficialAction.SELECT_MEAL -> rule.meal?.let { meal ->
            Button(enabled = enabled && !checking, onClick = {
                checking = true
                result = ""
                scope.launch {
                    try {
                        val check = com.life0423.carddock.platform.openMealPage(context, meal)
                        result = when (check) {
                            is DeviceResponse.NeedsUser -> check.message
                            is DeviceResponse.Failed -> check.message
                            is DeviceResponse.Ready -> "已打开「${meal.product}」规格页。"
                        }
                    } finally { checking = false }
                }
            }) { Text(if (checking) "核验中…" else "去选商品") }
            if (result.isNotBlank()) Text(result)
        }
        OfficialAction.OPEN_MCDONALDS -> Button(enabled = enabled, onClick = {
            val launch = context.packageManager.getLaunchIntentForPackage(MCD_PACKAGE)
            try {
                if (launch != null) context.startActivity(launch)
                else Toast.makeText(context, "未找到麦当劳官方 App", Toast.LENGTH_SHORT).show()
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, "麦当劳入口已变化，请重新试运行", Toast.LENGTH_SHORT).show()
            }
        }) { Text("打开麦当劳") }
        else -> fallback()
    }
}
