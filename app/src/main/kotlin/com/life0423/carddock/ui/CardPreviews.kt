package com.life0423.carddock.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.life0423.carddock.ai.comparisonMetrics
import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.model.CompareGroup
import com.life0423.carddock.core.rule.RuleStep
import com.life0423.carddock.core.session.PrototypeActions
import com.life0423.carddock.platform.MEITUAN_PACKAGE
import com.life0423.carddock.ui.theme.DockColors

@Composable
internal fun PreviewWindow(
    title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {},
    closeLabel: String = "返回",
    content: @Composable ColumnScope.() -> Unit,
) {
    // 详情与空间共用 Activity 窗口，返回时由上层恢复原筛选和滚动位置。
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack, Modifier.testTag("preview_back")) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, closeLabel)
            }
            Text(title, Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
            trailing()
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
internal fun CardDetail(card: CardRecord, actions: PrototypeActions) {
    var showRule by rememberSaveable(card.id) { mutableStateOf(false) }
    val rule = card.rule
    val page = card.page
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton({ actions.togglePin(card.id) }, Modifier.testTag("pin_${card.id}")) {
            Text(if (card.pinOrder == null) "置顶" else "取消置顶")
        }
    }
    if (page != null) {
        PageCardContent(page) { actions.setCover(card.id, it) }
        var confirmRemoval by rememberSaveable(card.id) { mutableStateOf(false) }
        TextButton({ confirmRemoval = true }) { Text("移除页面卡") }
        if (confirmRemoval) AlertDialog(
            onDismissRequest = { confirmRemoval = false }, title = { Text("移除这张页面卡？") },
            text = { Text("「${card.title}」会从空间移除。") },
            confirmButton = { TextButton({ actions.deleteCard(card.id); confirmRemoval = false }) { Text("移除") } },
            dismissButton = { TextButton({ confirmRemoval = false }) { Text("保留") } },
        )
    }
    CardCoverSettings(card) { actions.setCover(card.id, it) }
    if (rule == null) return
    FunctionCard(card, { actions.refreshCard(card.id) }, { actions.cancelCard(card.id) },
        { actions.resumeCard(card.id) }, { actions.deleteCard(card.id) })
    Surface(shape = RoundedCornerShape(16.dp), color = DockColors.Surface, border = BorderStroke(1.dp, DockColors.Outline)) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { showRule = !showRule }
                .testTag("toggle_rule").padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("查看执行规则", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Icon(if (showRule) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    if (showRule) "收起规则" else "展开规则", tint = DockColors.Muted)
            }
            if (showRule) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HorizontalDivider(color = DockColors.Outline)
                val target = if (rule.sourceId == MEITUAN_PACKAGE)
                    "美团订单 · 尾号 ${rule.targetKey.takeLast(4)}" else rule.targetKey
                if (card.originalPrompt.isNotBlank()) Text(card.originalPrompt, style = MaterialTheme.typography.bodyMedium)
                Text("规则 v${rule.schemaVersion} · ${rule.sourceId}\n目标：$target",
                    style = MaterialTheme.typography.bodySmall, color = DockColors.Muted)
                rule.steps.forEach { step ->
                    Text(when (step) {
                        is RuleStep.AssertTarget -> "核对指定来源对象"
                        is RuleStep.ReadField -> "读取${step.label}"
                        is RuleStep.RequireField -> "核验必需结果非空"
                    }, style = MaterialTheme.typography.bodySmall, color = DockColors.Muted)
                }
            }
        }
    }
}

/** 阅读、查看成员与组管理是独立页面，返回阅读页时保留字段和滚动位置。 */
@Composable
internal fun GroupPreview(group: CompareGroup, cards: List<CardRecord>, notice: String?, actions: PrototypeActions, onClose: () -> Unit) {
    val members = group.memberCardIds.map(cards.associateBy { it.id }::getValue)
    var assistant by rememberSaveable(group.id) { mutableStateOf(false) }
    val comparisonState = rememberSaveableStateHolder()
    var memberId by rememberSaveable(group.id) { mutableStateOf<String?>(null) }
    var managing by rememberSaveable(group.id) { mutableStateOf(false) }
    var renaming by rememberSaveable(group.name) { mutableStateOf(false) }
    var dissolving by rememberSaveable(group.id) { mutableStateOf(false) }
    var name by rememberSaveable(group.name) { mutableStateOf(group.name) }
    val pages = rememberSaveableStateHolder()
    val active = members.firstOrNull { it.id == memberId }
    if (active != null) {
        PreviewWindow(active.title, { memberId = null }) { CardDetail(active, actions) }
    } else if (!managing) {
        pages.SaveableStateProvider("comparison") {
            PreviewWindow(group.name, onClose, trailing = {
                TextButton({ managing = true }, Modifier.testTag("manage_group")) { Text("管理") }
            }) {
                TextButton({ assistant = !assistant }) { Text(if (assistant) "全部字段" else "按目标对比") }
                if (assistant) {
                    MemberLinks(members) { memberId = it }
                    comparisonState.SaveableStateProvider("assistant") { CompareWithAssistant(members, initiallyExpanded = true) }
                } else ComparisonFields(members) { memberId = it }
            }
        }
    } else {
        PreviewWindow("管理对比组", { managing = false }) {
            notice?.let { Text(it, color = DockColors.Error) }
            Surface(shape = RoundedCornerShape(16.dp), color = DockColors.Surface) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(group.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    TextButton({ name = group.name; renaming = true }) { Text("重命名") }
                }
            }
            Text("成员 · ${members.size} 张", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Surface(shape = RoundedCornerShape(16.dp), color = DockColors.Surface) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    members.forEachIndexed { index, member ->
                        if (index > 0) HorizontalDivider(color = DockColors.Outline)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(member.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton({ actions.removeGroupMember(group.id, member.id) }, Modifier.testTag("ungroup_${member.id}")) {
                                Text("移出")
                            }
                        }
                    }
                }
            }
            if (members.size == 2) Text("移出任一卡片会解散此组，两张卡片都将留在空间里。",
                color = DockColors.Muted, style = MaterialTheme.typography.bodySmall)
            TextButton({ dissolving = true }, Modifier.testTag("dissolve_group")) { Text("解散组", color = DockColors.Error) }
        }
    }
    if (renaming) AlertDialog(onDismissRequest = { renaming = false }, title = { Text("重命名对比组") },
        text = { Column {
            OutlinedTextField(name, { name = it }, label = { Text("组名，最多30字") }, singleLine = true)
            notice?.let { Text(it, color = DockColors.Error) }
        } },
        confirmButton = { TextButton({
            if (name.trim() == group.name) renaming = false else actions.renameGroup(group.id, name)
        }, enabled = name.trim().length in 1..30) { Text("保存") } },
        dismissButton = { TextButton({ renaming = false }) { Text("取消") } })
    if (dissolving) AlertDialog(onDismissRequest = { dissolving = false }, title = { Text("解散「${group.name}」？") },
        text = { Text("全部 ${members.size} 张卡片会保留在空间中。") },
        confirmButton = { TextButton({
            dissolving = false
            actions.dissolveGroup(group.id)
        }, Modifier.testTag("confirm_dissolve")) { Text("确认解散", color = DockColors.Error) } },
        dismissButton = { TextButton({ dissolving = false }) { Text("保留") } })
}

/** 指标按键和含义共同匹配。缺值保留空缺，点成员可以查看该卡的来源和刷新。 */
@Composable
private fun ComparisonFields(members: List<CardRecord>, onOpen: (String) -> Unit) {
    val metrics = comparisonMetrics(members)
    MemberLinks(members, onOpen)
    if (metrics.isEmpty()) Text("暂无已核验字段", color = DockColors.Muted)
    metrics.forEach { metric ->
        Surface(color = DockColors.Surface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, DockColors.Outline)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(metric.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                members.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { card ->
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(card.title, color = DockColors.Muted, style = MaterialTheme.typography.labelMedium)
                                Text(metric.value(card)?.takeIf { it.isNotBlank() } ?: "未取得",
                                    style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemberLinks(members: List<CardRecord>, onOpen: (String) -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        members.forEach { card ->
            OutlinedButton({ onOpen(card.id) }, Modifier.testTag("member_${card.id}")) {
                Text(card.title)
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.padding(start = 4.dp).size(18.dp))
            }
        }
    }
}
