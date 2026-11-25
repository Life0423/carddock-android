package com.life0423.carddock.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.life0423.carddock.R
import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.model.TemplateKind
import com.life0423.carddock.core.session.PrototypeActions
import com.life0423.carddock.core.session.PrototypeState
import com.life0423.carddock.ui.theme.DockColors

/** 置顶区只认手动置顶的卡片。对比组从同一份卡片列表读取成员及结果。 */
@Composable
internal fun SpaceScreen(
    state: PrototypeState,
    actions: PrototypeActions,
    onCreatePage: () -> Unit,
    onBrowseTemplates: () -> Unit,
    onOpenCard: (String) -> Unit,
    onOpenGroup: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var grouping by rememberSaveable { mutableStateOf(false) }
    val byId = state.cards.associateBy { it.id }
    val groupedIds = state.groups.flatMap { it.memberCardIds }.toSet()
    val matchingCards = state.cards.filter { card ->
        card.title.contains(query.trim(), ignoreCase = true) ||
            card.snapshot?.fields.orEmpty().any { it.value.contains(query.trim(), ignoreCase = true) }
    }
    val matchingIds = matchingCards.map { it.id }.toSet()
    val pinned = matchingCards.filter { it.pinOrder != null }.sortedBy { it.pinOrder }
    val singles = matchingCards.filter { it.id !in groupedIds && it.pinOrder == null }
    val groups = state.groups.filter { group ->
        group.name.contains(query.trim(), ignoreCase = true) || group.memberCardIds.any { it in matchingIds }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Image(painterResource(R.drawable.carddock_logo), null, Modifier.size(32.dp))
            Text("卡坞", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            IconButton({
                searchOpen = !searchOpen
                if (!searchOpen) query = ""
            }) {
                Icon(if (searchOpen) Icons.Rounded.Close else Icons.Rounded.Search,
                    if (searchOpen) "关闭搜索" else "搜索卡片")
            }
            Box {
                Button({ creating = true }, Modifier.testTag("open_creator"), contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("建卡")
                }
                DropdownMenu(creating, { creating = false }) {
                    DropdownMenuItem(text = { Text("创建任务卡") }, onClick = {
                        creating = false
                        actions.openCreator(null)
                    }, modifier = Modifier.testTag("create_card"))
                    DropdownMenuItem(text = { Text("保存页面") }, onClick = {
                        creating = false
                        onCreatePage()
                    }, modifier = Modifier.testTag("create_page"))
                    DropdownMenuItem(text = { Text("创建对比组") }, onClick = {
                        creating = false
                        grouping = true
                    }, modifier = Modifier.testTag("create_group"))
                }
            }
        }
        if (searchOpen) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth()
            .padding(horizontal = 20.dp).testTag("space_search"),
            label = { Text("搜索卡片、组或结果") }, singleLine = true)
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().testTag("space_list"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (pinned.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SpaceHeading("置顶") }
                items(pinned, key = { "pin:${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { card ->
                    SpaceCard(card, true) { onOpenCard(card.id) }
                }
            }
            if (groups.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SpaceHeading("对比组") }
                items(groups, key = { "group:${it.id}" }) { group ->
                    Surface(onClick = { onOpenGroup(group.id) }, modifier = Modifier.testTag("preview_${group.id}"),
                        color = DockColors.Raised, shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.heightIn(min = 142.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Icon(DockIcons.Layers, null, Modifier.size(24.dp), tint = DockColors.Mint)
                            Text(group.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(group.memberCardIds.joinToString(" · ") { byId.getValue(it).title },
                                style = MaterialTheme.typography.bodySmall, color = DockColors.Muted,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (singles.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SpaceHeading("单卡") }
                items(singles, key = { "card:${it.id}" }) { card -> SpaceCard(card, false) { onOpenCard(card.id) } }
            }
            if (pinned.isEmpty() && groups.isEmpty() && singles.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (query.isBlank()) "空间里还没有卡片" else "没有匹配的卡片",
                            style = MaterialTheme.typography.titleMedium)
                        if (query.isBlank()) TextButton(onBrowseTemplates, Modifier.testTag("browse_templates")) {
                            Text("浏览模板")
                        }
                    }
                }
            }
        }
    }
    if (grouping) GroupEditor(state, state.cards.filter { it.id !in groupedIds }, actions) { grouping = false }
}

@Composable
private fun SpaceHeading(title: String) {
    Text(title, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}

/** 演示图只用于演示来源。真实卡没选图片时用来源图标，不借用演示照片。 */
@Composable
private fun SpaceCard(card: CardRecord, featured: Boolean, onOpen: () -> Unit) {
    val cover = rememberCover(card.coverUri)
    val demoPhoto = if (card.rule?.sourceId?.startsWith("demo.") == true) when (card.kind) {
        TemplateKind.EXPRESS -> R.drawable.demo_parcels
        TemplateKind.DELIVERY -> R.drawable.demo_meal
        TemplateKind.TRAVEL -> R.drawable.demo_canal
        null -> null
    } else null
    val shape = RoundedCornerShape(if (featured) 24.dp else 20.dp)
    Surface(onClick = onOpen, modifier = Modifier.fillMaxWidth().testTag("preview_${card.id}"),
        color = DockColors.Surface, shape = shape, border = BorderStroke(1.dp, DockColors.Outline)) {
        Column {
            Box(Modifier.fillMaxWidth().height(if (featured) 155.dp else 87.dp)
                .clip(RoundedCornerShape(topStart = if (featured) 24.dp else 20.dp, topEnd = if (featured) 24.dp else 20.dp))
                .background(DockColors.Raised), contentAlignment = Alignment.Center) {
                if (cover != null) Image(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else if (demoPhoto != null) Image(painterResource(demoPhoto), "演示封面",
                    Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Icon(card.kind.icon(), null, Modifier.size(if (featured) 42.dp else 29.dp), tint = DockColors.Mint)
            }
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(card.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(card.snapshot?.fields?.firstOrNull()?.value ?: if (card.page != null) "页面直达" else "待运行",
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(card.resultSource(), style = MaterialTheme.typography.labelSmall, color = DockColors.Muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                (card.snapshot?.fetchedAt ?: card.page?.verifiedAt)?.let {
                    Text(formatShortTimestamp(it), style = MaterialTheme.typography.labelSmall, color = DockColors.Muted)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupEditor(
    state: PrototypeState, cards: List<CardRecord>, actions: PrototypeActions, onClose: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var comparing by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val comparisonState = rememberSaveableStateHolder()
    val initialGroups = rememberSaveable { ArrayList(state.groups.map { it.id }) }
    LaunchedEffect(state.groups) { if (state.groups.any { it.id !in initialGroups }) onClose() }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = DockColors.Surface) {
        if (comparing) {
            Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).imePadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton({ comparing = false }) { Text("返回选卡") }
                comparisonState.SaveableStateProvider("selection") {
                    CompareWithAssistant(cards.filter { it.id in selected }, initiallyExpanded = true)
                }
            }
        } else Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).imePadding().padding(horizontal = 22.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("创建对比组", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            OutlinedTextField(name, { name = it }, label = { Text("对比组名称") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("group_name"), isError = name.trim().length > 30)
            state.notice?.let { Text(it, color = DockColors.Error) }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                if (cards.size < 2) Text("至少需要两张未分组的卡片。", color = DockColors.Muted)
                cards.forEach { card ->
                    val checked = card.id in selected
                    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox) {
                        comparisonState.removeState("selection")
                        selected = if (it) selected + card.id else selected - card.id
                    }.testTag("select_${card.id}").padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        KindIcon(card.kind)
                        Text(card.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Checkbox(checked, onCheckedChange = null)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ comparing = true }, enabled = selected.size >= 2, modifier = Modifier.testTag("compare_selected")) {
                    Text("比较所选")
                }
                Button({ actions.createGroup(name, selected) }, enabled = name.trim().length in 1..30 && selected.size >= 2,
                    modifier = Modifier.weight(1f).testTag("save_group")) { Text("保存对比组") }
            }
        }
    }
}
