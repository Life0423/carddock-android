package com.life0423.carddock.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Search
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.life0423.carddock.core.model.PersonalTemplate
import com.life0423.carddock.core.model.TemplateDefinition
import com.life0423.carddock.data.SquareEntry
import com.life0423.carddock.data.SquareState
import com.life0423.carddock.data.SquareStore
import com.life0423.carddock.ui.theme.DockColors

/** 广场从已试运行的个人模板挑选内容，发布前单独确认公开资料。 */
@Composable
internal fun SquareScreen(store: SquareStore?, state: SquareState, personal: List<PersonalTemplate>, openLogin: () -> Unit, upload: Boolean, closeUpload: () -> Unit) {
    if (store == null || !state.available) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("尚未配置广场服务") }
        return
    }
    var query by rememberSaveable { mutableStateOf("") }
    var latest by rememberSaveable { mutableStateOf(false) }
    var preview by remember { mutableStateOf<TemplateDefinition?>(null) }
    var unpublish by remember { mutableStateOf<SquareEntry?>(null) }
    LaunchedEffect(store) {
        store.state.first { !it.busy }
        store.refresh(query, latest)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("square_list"),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        item {
            OutlinedTextField(query, { query = it.take(80) }, singleLine = true,
                placeholder = { Text("搜索模板") }, shape = RoundedCornerShape(14.dp),
                leadingIcon = { Icon(Icons.Rounded.Search, null) }, modifier = Modifier.fillMaxWidth(),
                trailingIcon = { TextButton({ store.refresh(query, latest) }, enabled = !state.busy) { Text("搜索") } },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { store.refresh(query, latest) }))
            TabRow(if (latest) 1 else 0, containerColor = DockColors.Background) {
                listOf("下载排行", "最新发布").forEachIndexed { index, title ->
                    Tab(selected = latest == (index == 1), enabled = !state.busy, onClick = {
                        latest = index == 1
                        store.refresh(query, latest)
                    }, text = { Text(title) })
                }
            }
        }
        if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.message?.let { message -> item {
            Column(Modifier.fillMaxWidth().padding(vertical = if (state.entries.isEmpty()) 32.dp else 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (message == SquareStore.OFFLINE_MESSAGE)
                    Icon(androidx.compose.ui.res.painterResource(com.life0423.carddock.R.drawable.nav_square), null,
                        Modifier.size(38.dp), tint = DockColors.Muted)
                Text(message, style = MaterialTheme.typography.bodyMedium)
                if (message == SquareStore.OFFLINE_MESSAGE)
                    OutlinedButton({ store.refresh(query, latest) }, enabled = !state.busy) { Text("重试") }
            }
        } }
        if (state.entries.isEmpty() && !state.busy && state.message == null) item {
            Text(if (query.isBlank()) "暂无公开模板" else "没有匹配的模板", color = DockColors.Muted)
        }
        itemsIndexed(state.entries, key = { _, entry -> entry.id }) { index, entry ->
            val installed = state.installed.firstOrNull { it.id == entry.id }
            val saved = state.favorites.any { it.id == entry.id }
            val owner = state.signedIn && entry.author == state.account
            val subtitle = listOfNotNull(
                entry.author, "第 ${entry.version} 版", "${entry.downloads} 次下载",
            ).joinToString(" · ")
            TemplateRow(entry.title, subtitle, entry.definition(), !state.busy && (owner || installed == null || installed.version < entry.version),
                when {
                    owner -> "下架"
                    installed == null -> "下载"
                    installed.version < entry.version -> "更新"
                    else -> "已下载"
                },
                {
                    when {
                        owner -> unpublish = entry
                        !state.signedIn -> openLogin()
                        else -> store.download(entry)
                    }
                }, "square_entry_${entry.id}", first = index == 0, last = index == state.entries.lastIndex,
                rank = if (latest) null else index + 1, extra = {
                    IconButton({ if (state.signedIn) store.toggleFavorite(entry) else openLogin() }, enabled = !state.busy) {
                        Icon(if (saved) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            if (saved) "取消收藏 ${entry.title}" else "收藏 ${entry.title}")
                    }
                },
            )
        }
    }
    if (upload) {
        val choices = personal.mapNotNull { it.published }.filter(store::canPublish)
        AlertDialog(
            onDismissRequest = { closeUpload() },
            title = { Text("选择已核验模板") },
            text = {
                if (choices.isEmpty()) Text("请先到「模板」创建并核验可公开的模板。")
                else LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    itemsIndexed(choices, key = { _, template -> template.id }) { _, template ->
                        TextButton({ closeUpload(); preview = template }, Modifier.fillMaxWidth()) { Text(template.title) }
                    }
                }
            },
            confirmButton = { TextButton({ closeUpload() }) { Text("关闭") } },
        )
    }
    preview?.let { template ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("确认公开发布") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(template.title)
                    if (template.description.isNotBlank()) Text(template.description)
                    Text("第 ${template.revision} 版 · ${state.account.orEmpty()}")
                    if (template.display.isNotEmpty()) Text("展示字段 · ${template.display.joinToString("、") { it.label }}")
                    Text("发布后，其他用户可以看到名称、说明、查询能力和展示字段名。")
                }
            },
            confirmButton = { TextButton({ store.publish(template); preview = null }) { Text("确认发布") } },
            dismissButton = { TextButton({ preview = null }) { Text("取消") } },
        )
    }
    unpublish?.let { entry ->
        AlertDialog(
            onDismissRequest = { unpublish = null },
            title = { Text("下架「${entry.title}」？") },
            text = { Text("下架后，其他人无法再下载。已下载的模板可继续使用。") },
            confirmButton = { TextButton({ store.unpublish(entry); unpublish = null }) { Text("确认下架") } },
            dismissButton = { TextButton({ unpublish = null }) { Text("取消") } },
        )
    }
}
