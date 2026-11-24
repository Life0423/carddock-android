package com.life0423.carddock.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.life0423.carddock.CardDockApplication
import com.life0423.carddock.core.session.PersonalArchive
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** 用系统文件选择器迁移用户勾选的条目，导入前显示可用模板、待复核草稿及空间卡数量。 */
@Composable
internal fun PersonalTransferDialog(close: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as CardDockApplication).container
    val controller = container.controller
    val state by controller.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var ids by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var pending by remember { mutableStateOf<PersonalArchive?>(null) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val resolver = context.contentResolver
    val limit = 4 * 1024 * 1024
    fun perform(block: suspend () -> Unit) {
        busy = true
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: IOException) { message = "文件读写失败，请检查文件位置和可用空间。" }
            catch (e: IllegalArgumentException) { message = "无法导入：文件版本或内容无效。" }
            catch (e: SecurityException) { message = "系统未允许访问此文件，请重新选择。" }
            finally { busy = false }
        }
    }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) perform {
            val selected = PersonalArchive(templates = state.personalTemplates.filter { "t/" + it.id in ids },
                cards = state.cards.filter { "c/" + it.id in ids }).portable(container::portableRule)
            withContext(Dispatchers.IO) {
                val bytes = Json.encodeToString(selected).encodeToByteArray()
                if (bytes.size > limit) throw IOException("文件过大")
                (resolver.openOutputStream(uri, "wt") ?: throw IOException()).use { it.write(bytes) }
            }
            message = "已导出 ${selected.templates.size} 个模板或草稿、${selected.cards.size} 张卡片。"
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) perform {
            pending = withContext(Dispatchers.IO) {
                val buffer = ByteArray(limit + 1)
                var size = 0
                (resolver.openInputStream(uri) ?: throw IOException()).use { stream ->
                    while (size < buffer.size) {
                        val read = stream.read(buffer, size, buffer.size - size)
                        if (read < 0) break
                        size += read
                    }
                }
                require(size <= limit)
                Json.decodeFromString<PersonalArchive>(buffer.decodeToString(0, size)).also { it.validate() }.portable(container::portableRule)
            }
            message = ""
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text("迁移模板与卡片") },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            val archive = pending
            if (archive == null) {
                Text("选择要导出的条目。文件包含所选标题和草稿原文。订单授权、模型密钥与登录会话留在本机。")
                val rows = state.personalTemplates.map { ("t/" + it.id) to (it.published?.title ?: it.edit?.title.orEmpty().ifBlank { "未命名草稿" }) } +
                    state.cards.map { ("c/" + it.id) to it.title }
                for ((id, name) in rows) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(id in ids, { on -> ids = if (on) ids + id else ids - id }, enabled = !busy)
                    Text((if (id.startsWith("t/")) "模板 · " else "空间 · ") + name)
                }
                OutlinedButton({ create.launch("卡坞个人迁移.json") }, enabled = ids.isNotEmpty() && !busy) { Text("导出所选") }
                TextButton({ open.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !busy) { Text("选择文件导入") }
            } else {
                Text("可用模板 ${archive.templates.count { it.published != null }} 个，草稿 ${archive.templates.count { it.edit != null }} 个，空间卡 ${archive.cards.size} 张。已有条目将跳过。")
                for (entry in archive.templates) {
                    val t = entry.published ?: entry.edit?.candidate
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        KindIcon(t?.kind ?: com.life0423.carddock.core.model.TemplateKind.QUERY,t?.rule,theme=t?.theme ?: entry.edit?.theme)
                        Text(t?.title ?: entry.edit?.title.orEmpty())
                    }
                }
                for (c in archive.cards) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    KindIcon(c.kind,c.rule,theme=c.theme);Text(c.title)
                }
                Text("设备专属路线需重新试运行。公开价格和演示卡可保留上次快照。")
                Button({ perform { message = controller.importPersonal(archive); pending = null } }, enabled = !busy) { Text("确认导入") }
            }
            if (message.isNotBlank()) Text(message)
        } }, confirmButton = { TextButton(close, enabled = !busy) { Text("关闭") } })
}
