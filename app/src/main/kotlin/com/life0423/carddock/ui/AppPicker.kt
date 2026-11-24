package com.life0423.carddock.ui

import android.content.ComponentName
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.life0423.carddock.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 同名入口并排展示图标和组件名，便于用户区分。 */
@Composable
internal fun AppEntryLabel(app: LocalApp, detail: Boolean) {
    val pm = LocalContext.current.packageManager
    val icon = remember(app.key) {
        try {
            pm.getActivityIcon(ComponentName.unflattenFromString(app.key)!!).toBitmap(64, 64).asImageBitmap()
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) { null }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (icon != null) Image(icon, null, Modifier.size(26.dp))
        Column {
            Text(app.label)
            if (detail) Text(app.key, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 每次展开读取当前桌面。空间用于直接启动，新建页用于选择模板的应用身份。 */
@Composable
internal fun AppPicker(onDismiss: () -> Unit, onTask: ((String) -> Unit)? = null, onSelect: (LocalApp) -> Unit) {
    val context = LocalContext.current
    val apps by produceState<List<LocalApp>?>(null, context) {
        value = withContext(Dispatchers.IO) { context.launchableApps() }
    }
    var query by remember { mutableStateOf("") }
    val name = query.trim().replaceFirst(openPrefix, "").trim()
    val matches = if (name.isEmpty()) apps.orEmpty() else apps.orEmpty().byAppName(name)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择本机应用") }, text = {
        Column {
            OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("输入应用名称或打开指令") })
            when {
                apps == null -> LinearProgressIndicator()
                onTask != null && matches.isEmpty() && apps.orEmpty().any { it.label in query } ->
                    TextButton({ onTask(query) }) { Text("让 Mika 理解后续操作") }
                matches.isEmpty() -> Text("应用识别失败：没有匹配的可启动应用。")
                else -> LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(matches, key = { it.key }) { app ->
                        TextButton({ onSelect(app) }, modifier = Modifier.fillMaxWidth()) {
                            AppEntryLabel(app, matches.count { appNameKey(it.label) == appNameKey(app.label) } > 1)
                        }
                    }
                }
            }
        }
    }, confirmButton = {
        val exact = matches.singleOrNull()?.takeIf { name.isNotBlank() && appNameKey(it.label) == appNameKey(name) }
        if (exact != null) TextButton({ onSelect(exact) }) { Text("确定") }
    }, dismissButton = { TextButton(onDismiss) { Text("取消") } })
}
