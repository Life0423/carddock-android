package com.life0423.carddock.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.life0423.carddock.ui.theme.DockColors
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.life0423.carddock.BookSearch
import com.life0423.carddock.TAOBAO
import com.life0423.carddock.officialBookUrl
import com.life0423.carddock.core.model.FieldValue

/** 参数一次提交给现有草稿控制器，旧预览由该控制器失效。 */
@Composable internal fun BookParameters(search: BookSearch, enabled: Boolean, choose: (BookSearch) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    TextButton({ editing = true }, enabled = enabled) { Text("修改关键词和数量") }
    if (!editing) return
    var keyword by rememberSaveable(search.key) { mutableStateOf(search.keyword) }
    var count by rememberSaveable(search.key) { mutableStateOf(search.count.toString()) }
    val next = BookSearch(keyword.trim(), count.toIntOrNull() ?: 0)
    AlertDialog(onDismissRequest = { editing = false }, title = { Text("淘宝找书") },
        text = { Column {
            OutlinedTextField(keyword, { keyword = it }, singleLine = true, label = { Text("关键词或题材") })
            OutlinedTextField(count, { count = it }, singleLine = true, label = { Text("数量（1 至 5 本）") })
        } }, confirmButton = { TextButton({ editing = false; choose(next) }, enabled = next.valid) { Text("使用这些参数") } },
        dismissButton = { TextButton({ editing = false }) { Text("取消") } })
}

/** 仅用户点击后交给前台淘宝，后台执行过程中不切换用户屏幕。 */
@Composable internal fun BookFields(fields: List<FieldValue>) {
    val context = LocalContext.current
    var error by remember { mutableStateOf("") }
    // 用户可隐藏、改名或重排字段。按稳定字段键将商品链接放回对应的书目。
    val visible = fields.associateBy { it.key }
    val rendered = mutableSetOf<String>()
    fields.forEach { field ->
        if (!rendered.add(field.key)) return@forEach
        val number = field.key.removePrefix("book").removePrefix("url").toIntOrNull()
        if (number == null) {
            if (field.label != "核对进度") Text(field.label, style = MaterialTheme.typography.labelMedium)
            Text(field.value, style = MaterialTheme.typography.titleMedium, color = DockColors.Mint)
            return@forEach
        }
        HorizontalDivider(color = DockColors.Outline)
        val book = visible["book$number"]
        val link = visible["url$number"]
        if (book != null) {
            rendered.add(book.key)
            val lines = book.value.lines()
            if (book.label != "图书 $number") Text(book.label, style = MaterialTheme.typography.labelMedium)
            Text(lines.first(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            val reason = lines.indexOfFirst { it.startsWith("推荐依据 · ") }
            val description = (if (reason > 0) lines.drop(reason) else lines.drop(1)).joinToString("\n")
            if (description.isNotBlank()) Text(description.removePrefix("推荐依据 · "), style = MaterialTheme.typography.bodyMedium)
        }
        if (link != null) {
            rendered.add(link.key)
            if (officialBookUrl(link.value)) {
                val item = book?.value?.substringBefore('\n') ?: link.label
                OutlinedButton(onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.value)).setPackage(TAOBAO))
                        error = ""
                    } catch (_: ActivityNotFoundException) { error = "当前淘宝无法打开该分享链接。" }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                    contentDescription = "打开淘宝商品，$item"
                }, shape = DockButtonShape, border = BorderStroke(1.dp, DockColors.Mint)) {
                    Text(if (link.label == "商品链接 $number") "打开淘宝商品" else link.label)
                    Spacer(Modifier.width(8.dp))
                    Text("↗", modifier = Modifier.clearAndSetSemantics {})
                }
            } else Text(link.value, style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (error.isNotBlank()) Text(error)
}

/** 展开后可核对完整的商品标题及原始字段，精简票面仍保留数据来源。 */
@Composable internal fun BookEvidence(fields: List<FieldValue>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    OutlinedButton({ expanded = !expanded }, Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = DockButtonShape) {
        Text(if (expanded) "收起核验依据" else "查看核验依据")
    }
    if (expanded) Text(fields.joinToString("\n\n") { "${it.label} · ${it.value}" })
}
