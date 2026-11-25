package com.life0423.carddock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.life0423.carddock.ai.ModelSettings
import com.life0423.carddock.ai.ModelSummary
import com.life0423.carddock.ui.theme.DockColors
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import kotlinx.coroutines.launch

/** 配置只在使用助手时生效。界面只回填地址和模型名，保存时重新输入密钥。 */
@Composable
internal fun ModelSettingsScreen() {
    val context = LocalContext.current
    val settings = remember(context) { ModelSettings(context) }
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf<ModelSummary?>(null) }
    var editing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var endpoint by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }

    LaunchedEffect(settings) {
        error = updateModelSettings {
            summary = settings.load()?.let { ModelSummary(it.endpoint, it.model) }
        }
        busy = false
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("模型配置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Surface(shape = RoundedCornerShape(18.dp), color = DockColors.Surface) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        summary?.model ?: if (busy) "正在读取" else "未配置",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                    )
                    if (!editing) TextButton({
                        endpoint = summary?.endpoint.orEmpty()
                        model = summary?.model.orEmpty()
                        key = ""
                        editing = true
                    }, enabled = !busy, modifier = Modifier.testTag("edit_model")) {
                        Text(if (summary == null) "配置" else "修改")
                    }
                }
                if (editing) {
                    OutlinedTextField(
                        endpoint, { endpoint = it }, modifier = Modifier.fillMaxWidth().testTag("model_endpoint"),
                        label = { Text("OpenAI 兼容服务地址") }, placeholder = { Text("https://api.openai.com/v1") },
                        enabled = !busy, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    OutlinedTextField(
                        model, { model = it }, modifier = Modifier.fillMaxWidth().testTag("model_name"),
                        label = { Text("模型名称") }, enabled = !busy, singleLine = true,
                    )
                    OutlinedTextField(
                        key, { key = it }, modifier = Modifier.fillMaxWidth().testTag("model_key"),
                        label = { Text("API 密钥") }, enabled = !busy, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button({
                            busy = true
                            scope.launch {
                                error = updateModelSettings {
                                    summary = settings.save(endpoint, model, key)
                                    key = ""
                                    editing = false
                                }
                                busy = false
                            }
                        }, enabled = !busy && endpoint.isNotBlank() && model.isNotBlank() && key.isNotBlank(),
                            modifier = Modifier.testTag("save_model")) { Text("保存") }
                        TextButton({
                            editing = false
                            key = ""
                        }, enabled = !busy) { Text("取消") }
                        if (summary != null) TextButton({
                            busy = true
                            scope.launch {
                                error = updateModelSettings {
                                    settings.clear()
                                    summary = null
                                    key = ""
                                    editing = false
                                }
                                busy = false
                            }
                        }, enabled = !busy) { Text("清除配置") }
                    }
                } else summary?.endpoint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = DockColors.Muted) }
                error?.let { Text(it, color = DockColors.Error, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/** 配置操作失败时使用本地提示。服务地址、密码和异常正文保持在界面之外。 */
private suspend fun updateModelSettings(action: suspend () -> Unit): String? = try {
    action()
    null
} catch (_: IOException) {
    "模型配置无法读写，请稍后重试。"
} catch (_: IllegalArgumentException) {
    "请检查 HTTPS 服务地址、模型名称和密钥。"
} catch (_: GeneralSecurityException) {
    "设备密钥不可用，请重试。"
} catch (_: ProviderException) {
    "设备密钥不可用，请重试。"
}
