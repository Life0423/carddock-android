package com.life0423.carddock.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
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
    var section by rememberSaveable { mutableIntStateOf(0) }
    val latest = section == 1
    val favorites = section == 2
    val entries = if (favorites) state.favorites.filter { it.title.contains(query.trim(), ignoreCase = true) } else state.entries
    var reviewedId by rememberSaveable { mutableStateOf<String?>(null) }
    var submitted by rememberSaveable { mutableStateOf(false) }
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
            if (!favorites) OutlinedTextField(query, { query = it.take(80) }, singleLine = true,
                placeholder = { Text("搜索模板") }, shape = RoundedCornerShape(14.dp),
                leadingIcon = { Icon(Icons.Rounded.Search, null) }, modifier = Modifier.fillMaxWidth(),
                trailingIcon = { TextButton({ store.refresh(query, latest) }, enabled = !state.busy) { Text("搜索") } },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { store.refresh(query, latest) }))
            TabRow(section, containerColor = DockColors.Background) {
                listOf("下载排行", "最新发布", "我的收藏").forEachIndexed { index, title ->
                    Tab(selected = section == index, onClick = {
                        section = index
                        if (index == 2) query = ""
                        if (index != 2) store.refresh(query, index == 1)
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
        if (entries.isEmpty() && !state.busy && (favorites || state.message == null)) item {
            Text(if (query.isNotBlank()) "没有匹配的模板" else if (favorites) "暂无收藏模板" else "暂无公开模板", color = DockColors.Muted)
        }
        itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
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
                }, "square_entry_${entry.id}", first = index == 0, last = index == entries.lastIndex,
                rank = if (section == 0) index + 1 else null, extra = {
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
        val template = choices.firstOrNull { it.id == reviewedId }
        val posted = submitted && !state.busy && state.message?.startsWith("模板已发布") == true
        val close = { reviewedId = null; submitted = false; closeUpload() }
        AlertDialog(
            onDismissRequest = { if (!state.busy || !submitted) close() },
            containerColor = DockColors.Surface,
            title = { Text(when { posted -> "发布完成"; template != null -> "公开内容预览"; else -> "上传模板" }) },
            text = {
                Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FlowSteps(listOf("选模板", "公开预览", "发布"), if (posted) 2 else if (template != null) 1 else 0)
                    if (posted) Text(checkNotNull(state.message))
                    else if (template == null) {
                        if (choices.isEmpty()) Text("暂无可公开的已核验模板")
                        choices.forEach { choice ->
                            TemplateRow(choice.title, choice.description, choice, true, "选择",
                                { reviewedId = choice.id; submitted = false }, "upload_${choice.id}")
                        }
                        val local = personal.count { it.published != null } - choices.size
                        if (local > 0) Text("另有 ${local} 份模板仅限本机使用", color = DockColors.Muted)
                    } else {
                        Surface(shape = RoundedCornerShape(16.dp), color = DockColors.Ticket) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                KindIcon(template.kind, template.rule, theme = template.theme)
                                Text(template.title, style = MaterialTheme.typography.titleLarge)
                                Text("第 ${template.revision} 版 · ${state.account.orEmpty()}")
                            }
                        }
                        Text(template.description)
                        val labels = template.display.map { it.label }.ifEmpty {
                            template.rule.steps.filterIsInstance<com.life0423.carddock.core.rule.RuleStep.ReadField>().map { it.label }
                        }
                        Text("展示字段 · " + labels.joinToString("、"))
                        Text("发布后其他人可以查看和下载。请检查名称和说明中是否含有个人信息。", color = DockColors.Muted)
                        if (submitted) {
                            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                            else state.message?.let { Text(it, color = DockColors.Error) }
                        }
                    }
                }
            },
            confirmButton = {
                if (template == null || posted) TextButton(onClick = close) { Text(if (posted) "完成" else "关闭") }
                else Button(onClick = { submitted = true; store.publish(template) }, enabled = !state.busy && state.signedIn) {
                    Text(if (submitted && !state.busy) "重试发布" else "确认发布")
                }
            },
            dismissButton = { if (template != null && !posted)
                TextButton(onClick = { reviewedId = null; submitted = false }, enabled = !state.busy) { Text("重选模板") }
            },
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
