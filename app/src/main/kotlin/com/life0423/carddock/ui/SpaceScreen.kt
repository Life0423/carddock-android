package com.life0423.carddock.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.life0423.carddock.R
import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.model.CompareGroup
import com.life0423.carddock.core.model.TemplateKind
import com.life0423.carddock.core.rule.RuleStep
import com.life0423.carddock.core.session.PrototypeActions
import com.life0423.carddock.core.session.PrototypeState
import com.life0423.carddock.ui.theme.DockColors

/** 首页只保存筛选和路由；组关系、规则及执行状态全部来自控制器。详情覆盖列表以保留滚动位置。 */
@Composable
internal fun SpaceScreen(state: PrototypeState, actions: PrototypeActions) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("全部") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var createMenu by remember { mutableStateOf(false) }
    var groupEditor by remember { mutableStateOf(false) }
    var cardId by rememberSaveable { mutableStateOf<String?>(null) }
    var groupId by rememberSaveable { mutableStateOf<String?>(null) }
    val groupedIds = state.groups.flatMap { it.memberCardIds }.toSet()
    val firstMembers = state.groups.associateBy { it.memberCardIds.first() }
    // 组成员在持久化列表内连续、有序；仅在第一成员位置生成组入口。
    val entries = state.cards.filter { it.id !in groupedIds || it.id in firstMembers }
    val shown = entries.filter { first ->
        val group = firstMembers[first.id]
        val members = group?.memberCardIds?.mapNotNull { id -> state.cards.find { it.id == id } } ?: listOf(first)
        (category == "全部" || members.any { it.categoryLabel() == category }) &&
            (query.isBlank() || group?.name?.contains(query.trim(), true) == true || members.any { it.matches(query) })
    }
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.carddock_logo), "卡坞", Modifier.size(34.dp))
            Text("日常生活", Modifier.weight(1f).padding(start = 9.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton({ searchOpen = !searchOpen }) { Icon(Icons.Rounded.Search, "搜索卡片") }
            Box {
                TextButton({ createMenu = true }, Modifier.testTag("open_creator")) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(20.dp)); Text("新建")
                }
                DropdownMenu(createMenu, { createMenu = false }) {
                    DropdownMenuItem(text = { Text("创建卡片") }, onClick = { createMenu = false; actions.openCreator(null) }, modifier = Modifier.testTag("create_card"))
                    DropdownMenuItem(text = { Text("创建对比组") }, onClick = { createMenu = false; groupEditor = true }, modifier = Modifier.testTag("create_group"))
                }
            }
        }
        if (searchOpen) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("space_search"), label = { Text("搜索卡片、组或结果") }, singleLine = true)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            listOf("全部", "吃喝", "出行", "办事").forEach { label ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TextButton({ category = label }, Modifier.testTag("category_$label")) {
                        Text(label, color = if (category == label) DockColors.Mint else DockColors.Muted)
                    }
                    Box(Modifier.width(38.dp).height(3.dp).background(if (category == label) DockColors.Mint else Color.Transparent))
                }
            }
        }
        HorizontalDivider(color = DockColors.Outline)
        if (shown.isEmpty()) {
            Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (state.cards.isEmpty()) "从一张自己的卡片开始" else "没有匹配的卡片")
                TextButton({ if (state.cards.isEmpty()) actions.openCreator(null) else { query = ""; category = "全部" } }) {
                    Text(if (state.cards.isEmpty()) "创建卡片" else "清除筛选")
                }
            }
        }
        LazyVerticalGrid(GridCells.Fixed(2), Modifier.testTag("space_list"), contentPadding = PaddingValues(12.dp, 28.dp, 12.dp, 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            items(shown, key = { it.id }) { card ->
                val group = firstMembers[card.id]
                val members = group?.memberCardIds?.mapNotNull { id -> state.cards.find { it.id == id } } ?: listOf(card)
                FeedTile(card, group, members, query) { if (group == null) cardId = card.id else groupId = group.id }
            }
        }
    }
    if (groupEditor) GroupEditor(state, state.cards.filter { it.id !in groupedIds }, actions, { groupEditor = false })
    val group = state.groups.find { it.id == groupId }
    if (group != null) GroupPreview(group, state.cards, state.notice, actions, { groupId = null })
    val card = state.cards.find { it.id == cardId }
    if (card != null) PreviewWindow(card.title, { cardId = null }) { CardDetail(card, actions) }
}

/** 演示来源使用标明身份的交接照片；真实来源缺少封面时显示占位，不伪装读取结果。 */
@Composable
private fun FeedTile(card: CardRecord, group: CompareGroup?, members: List<CardRecord>, query: String, onOpen: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column {
        Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).clickable(onClick = onOpen).testTag("preview_${group?.id ?: card.id}")) {
            members.take(3).reversed().forEachIndexed { index, member ->
                val layer = members.take(3).lastIndex - index
                Box(Modifier.fillMaxSize(if (group == null) 1f else .84f).align(Alignment.BottomStart).offset(x = (layer * 10).dp, y = (-layer * 10).dp).clip(RoundedCornerShape(9.dp)).background(if (layer % 2 == 0) DockColors.Raised else DockColors.Surface)) {
                    val demo = member.rule.sourceId.startsWith("demo")
                    if (demo) Image(painterResource(when (member.kind) {
                        TemplateKind.EXPRESS -> R.drawable.demo_parcels
                        TemplateKind.DELIVERY -> R.drawable.demo_meal
                        TemplateKind.TRAVEL -> R.drawable.demo_canal
                    }), "演示封面，不是来源读取结果", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        KindIcon(member.kind)
                        Text(member.title, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text(if (group != null && layer == 0) "对比 · ${members.size} 张${if (demo) " · 演示" else ""}" else if (member.rule.sourceId.startsWith("demo")) "演示封面" else "暂无来源封面", Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = .65f)).padding(6.dp), style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(group?.name ?: card.title, Modifier.weight(1f).clickable(onClick = onOpen).padding(top = 8.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Box {
                IconButton({ menu = true }, Modifier.size(48.dp)) { Icon(DockIcons.MoreHoriz, "${group?.name ?: card.title}的更多操作", tint = DockColors.Muted) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(if (group == null) "查看结果与规则" else "查看与管理成员") }, onClick = { menu = false; onOpen() })
                }
            }
        }
        val summary = if (group != null) {
            val hits = members.filter { it.matches(query) }
            (if (query.isNotBlank() && hits.isNotEmpty()) "组内：" else "") + (if (query.isNotBlank() && hits.isNotEmpty()) hits else members).joinToString(" / ") { it.title }
        } else card.snapshot?.let { "${it.sourceLabel} · ${formatTimestamp(it.fetchedAt)}" } ?: "尚无核验结果"
        if (group == null) card.run?.let { Text(it.phase.label(), style = MaterialTheme.typography.labelSmall, color = if (it.phase == com.life0423.carddock.core.model.RunPhase.FAILED) DockColors.Error else DockColors.Mint) }
        Text(summary, style = MaterialTheme.typography.bodySmall, color = DockColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** 完整结构化结果与当前规则可查，刷新仍走现有执行器。 */
@Composable
private fun CardDetail(card: CardRecord, actions: PrototypeActions) {
    FunctionCard(card, { actions.refreshCard(card.id) }, { actions.cancelCard(card.id) }, { actions.resumeCard(card.id) }, { actions.deleteCard(card.id) })
    Text("结构化结果", style = MaterialTheme.typography.labelMedium, color = DockColors.Muted)
    Text("规则 v${card.rule.schemaVersion} · ${card.rule.sourceId}\n目标：${card.rule.targetKey}", style = MaterialTheme.typography.bodySmall)
    if (card.originalPrompt.isNotBlank()) Text("创建需求：${card.originalPrompt}", style = MaterialTheme.typography.bodyMedium)
    card.rule.steps.forEach { step ->
        Text(when (step) {
            is RuleStep.AssertTarget -> "核对指定来源对象"
            is RuleStep.ReadField -> "读取：${step.label}"
            is RuleStep.RequireField -> "核验必需结果非空"
        }, style = MaterialTheme.typography.bodySmall, color = DockColors.Muted)
    }
}

/** 全屏原生预览，不嵌入或伪装第三方 App。 */
@Composable
private fun PreviewWindow(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = DockColors.Background) {
            Column(Modifier.safeDrawingPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onBack, Modifier.testTag("preview_back")) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
                    Text(title, Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.titleLarge)
                }
                Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
            }
        }
    }
}

/** 成员切换不执行任务；展开后返回当前成员。解散导致组消失时由首页自然关闭预览。 */
@Composable
private fun GroupPreview(group: CompareGroup, cards: List<CardRecord>, notice: String?, actions: PrototypeActions, onClose: () -> Unit) {
    var selected by rememberSaveable(group.id) { mutableStateOf(group.memberCardIds.first()) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var name by remember(group.id) { mutableStateOf(group.name) }
    LaunchedEffect(group.name) { name = group.name; renaming = false }
    val active = cards.first { it.id == (selected.takeIf { id -> id in group.memberCardIds } ?: group.memberCardIds.first()) }
    val back = { if (expanded) expanded = false else onClose() }
    PreviewWindow(if (expanded) active.title else group.name, back) {
        notice?.let { Text(it, color = DockColors.Error) }
        if (!expanded) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                group.memberCardIds.forEach { id ->
                    val member = cards.first { it.id == id }
                    FilterChip(selected = active.id == id, onClick = { selected = id }, label = { Text(member.title) }, modifier = Modifier.testTag("member_$id"))
                }
            }
            Row {
                TextButton({ expanded = true }) { Text("单独展开") }
                TextButton({ renaming = true }) { Text("重命名") }
                TextButton({ actions.dissolveGroup(group.id) }, Modifier.testTag("dissolve_group")) { Text("解散组") }
            }
        }
        CardDetail(active, actions)
        if (!expanded) {
            HorizontalDivider()
            Text("管理成员", style = MaterialTheme.typography.titleMedium)
            group.memberCardIds.forEach { id ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(cards.first { it.id == id }.title, Modifier.weight(1f))
                    TextButton({ actions.removeGroupMember(group.id, id) }, Modifier.testTag("ungroup_$id")) { Text("移出") }
                }
            }
            Text("移出或解散只改变分组，保留每张卡片。", style = MaterialTheme.typography.bodySmall, color = DockColors.Muted)
        }
    }
    if (renaming) AlertDialog(onDismissRequest = { renaming = false }, title = { Text("重命名对比组") }, text = { Column { OutlinedTextField(name, { name = it }, label = { Text("组名，最多30字") }); notice?.let { Text(it, color = DockColors.Error) } } }, confirmButton = { TextButton({ actions.renameGroup(group.id, name); if (name.trim() == group.name) renaming = false }, enabled = name.trim().length in 1..30) { Text("保存") } }, dismissButton = { TextButton({ renaming = false }) { Text("取消") } })
}

/** 从独立卡选择引用；保存由控制器统一校验并原子提交。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupEditor(state: PrototypeState, cards: List<CardRecord>, actions: PrototypeActions, onClose: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptyList<String>()) }
    val initialGroups = remember { state.groups.map { it.id }.toSet() }
    LaunchedEffect(state.groups) {
        if (state.groups.any { it.id !in initialGroups }) onClose()
    }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = DockColors.Surface) {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).imePadding().padding(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("创建对比组", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(name, { name = it }, label = { Text("对比组名称") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("group_name"))
            Text("至少选择两张独立卡片，组名最多 30 字")
            state.notice?.let { Text(it, color = DockColors.Error) }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                cards.forEach { card ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(card.id in selected, { checked -> selected = if (checked) selected + card.id else selected - card.id }, Modifier.testTag("select_${card.id}"))
                        Text(card.title)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClose) { Text("取消") }
                TextButton({ actions.createGroup(name, selected) }, enabled = name.trim().length in 1..30 && selected.size >= 2, modifier = Modifier.testTag("save_group")) { Text("保存对比组") }
            }
        }
    }
}

private fun CardRecord.categoryLabel() = when (kind) { TemplateKind.EXPRESS -> "办事"; TemplateKind.DELIVERY -> "吃喝"; TemplateKind.TRAVEL -> "出行" }
private fun CardRecord.matches(query: String): Boolean = (title + " " + rule.sourceId + " " + snapshot?.fields.orEmpty().joinToString { it.value }).contains(query.trim(), true)
