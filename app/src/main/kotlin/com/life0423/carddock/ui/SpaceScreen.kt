package com.life0423.carddock.ui

import com.life0423.carddock.core.model.displayFields

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.life0423.carddock.core.model.TemplateKind
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.life0423.carddock.cainiaoRuleChange
import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.model.RunPhase
import com.life0423.carddock.core.session.PrototypeActions
import com.life0423.carddock.core.session.SequenceStatus
import com.life0423.carddock.platform.JD_PACKAGE
import com.life0423.carddock.platform.MEITUAN_PACKAGE
import com.life0423.carddock.ui.theme.DockColors

/** 查询和选购共用同一列表。收藏只调整视觉顺序。 */
@Composable
internal fun SpaceScreen(
    cards: List<CardRecord>, actions: PrototypeActions, sequence: SequenceStatus?,
    onOpenCard: (String) -> Unit, shoppingSaved: Boolean, shoppingFavorite: Boolean,
    onToggleShoppingFavorite: () -> Unit, onOpenShopping: (() -> Unit)?, onAdd: () -> Unit,
) {
    val nativeIds = cards.filterNot { it.rule.sourceId.startsWith("demo.") }.map { it.id }
    val ordered: List<CardRecord?> = cards.sortedByDescending { it.favorite }.toMutableList<CardRecord?>().apply {
        if (onOpenShopping != null) add(if (shoppingFavorite) cards.count { it.favorite } else size, null)
    }
    var menu by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.testTag("space_list"),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (sequence != null) item { SequenceControls(sequence, actions, nativeIds) }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("点卡片即可查询", Modifier.weight(1f), color = DockColors.Muted)
                if (sequence == null && nativeIds.isNotEmpty()) Box {
                    IconButton(onClick = { menu = true }) { Icon(DockIcons.MoreHoriz, "空间操作") }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("顺序核验") }, onClick = {
                            menu = false
                            actions.refreshSequence(nativeIds)
                        })
                    }
                }
            }
        }
        if (cards.isEmpty() && onOpenShopping == null) item {
            Surface(color = DockColors.Surface, shape = RoundedCornerShape(16.dp)) {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("还没有功能卡", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onAdd, enabled = sequence == null) { Text("添加功能卡") }
                }
            }
        }
        items(ordered, key = { it?.id ?: "shopping" }) { card ->
            if (card == null) {
                ShoppingSummary(shoppingSaved, shoppingFavorite, onToggleShoppingFavorite, checkNotNull(onOpenShopping))
                return@items
            }
            val demo = card.rule.sourceId.startsWith("demo.")
            val snapshot = card.snapshot
            val phase = card.run?.phase ?: if (snapshot == null) RunPhase.IDLE else RunPhase.SUCCEEDED
            val price = card.appPrice
            val fields = snapshot?.displayFields(card.display).orEmpty()
            val summary = if (card.display.isEmpty() && card.rule.sourceId == JD_PACKAGE) {
                fields.firstOrNull { it.key == "variant" }?.value
            } else fields.firstOrNull()?.value
            val previous = snapshot != null && phase != RunPhase.SUCCEEDED && phase != RunPhase.IDLE
            var cardMenu by remember { mutableStateOf(false) }
            Surface(
                onClick = { actions.refreshCard(card.id) },
                enabled = !phase.isBusy() && sequence == null,
                modifier = Modifier.fillMaxWidth().testTag("refresh_${card.id}"),
                color = DockColors.Surface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, if (phase.isBusy()) DockColors.Mint else DockColors.Outline),
            ) {
                Row(
                    Modifier.padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    KindIcon(card.kind)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(if (demo) "${card.title} · 演示" else card.title,
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        if (phase == RunPhase.NEEDS_USER && card.run?.message == com.life0423.carddock.platform.FILTER_APPEARANCE_REQUIRED) {
                            TextButton(onClick = { actions.compareFilterForCard(card.id) }, enabled = sequence == null) {
                                Text("允许本次在本机比对筛选项")
                            }
                        }
                        Text(summary ?: "暂无核验结果", style = MaterialTheme.typography.bodySmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (price != null) {
                            Text(
                                if (price.currency == "CNY") "¥${price.amount} · App 展示价"
                                else "${price.amount} ${price.currency} · App 展示价",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                (if (card.priceVerificationFailed) "上次有效价格 " else "价格核验 ") +
                                    formatTimestamp(price.verifiedAt),
                                color = if (card.priceVerificationFailed) DockColors.Amber else DockColors.Muted,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        if (previous || phase != RunPhase.SUCCEEDED || price == null) {
                            val shown = when {
                                previous -> "${phase.label()} · 保留上次结果"
                                phase == RunPhase.SUCCEEDED && snapshot != null -> "核验 " + formatTimestamp(snapshot.fetchedAt)
                                phase == RunPhase.RUNNING -> "正在查询"
                                else -> phase.label()
                            }
                            Text(shown + if (previous) " · " + formatTimestamp(snapshot.fetchedAt) else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (previous || phase == RunPhase.NEEDS_USER) DockColors.Amber else DockColors.Muted)
                        }
                        if (phase != RunPhase.SUCCEEDED) card.run?.message?.takeIf { it.isNotBlank() }?.let {
                            Text(it, color = DockColors.Muted, style = MaterialTheme.typography.labelSmall)
                        }
                        if (phase == RunPhase.RUNNING) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row {
                            FavoriteButton(card.title, card.favorite) { actions.toggleFavorite(card.id) }
                            Box {
                                IconButton(onClick = { cardMenu = true }, modifier = Modifier.testTag("space_menu_${card.id}")) {
                                    Icon(DockIcons.MoreHoriz, "${card.title}的更多操作")
                                }
                                DropdownMenu(cardMenu, onDismissRequest = { cardMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("查看详情") },
                                        modifier = Modifier.testTag("open_card_${card.id}"),
                                        onClick = { cardMenu = false; onOpenCard(card.id) },
                                    )
                                }
                            }
                        }
                        if (phase.isBusy() && sequence == null) {
                            TextButton(onClick = { actions.cancelCard(card.id) }) { Text("取消") }
                        }
                    }
                }
            }
        }
    }
}

/** 星标是独立操作，不触发卡片查询。 */
@Composable
private fun FavoriteButton(title: String, favorite: Boolean, onToggle: () -> Unit) {
    IconToggleButton(checked = favorite, onCheckedChange = { onToggle() }) {
        Icon(Icons.Rounded.Star, if (favorite) "取消收藏$title" else "收藏$title",
            tint = if (favorite) DockColors.Mint else DockColors.Muted)
    }
}

/** 选购入口引用原任务，点击后打开对比并按已核验的商品身份刷新。 */
@Composable
private fun ShoppingSummary(saved: Boolean, favorite: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    Surface(onClick = onOpen, modifier = Modifier.fillMaxWidth().testTag("open_shopping"), color = DockColors.Surface,
        shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, DockColors.Outline)) {
        Row(Modifier.padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            KindIcon(TemplateKind.SHOPPING)
            Column(Modifier.weight(1f)) {
                Text("显示器选购", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(if (saved) "京东 A / B · 已保存对比" else "京东 A / B · 商品对比",
                    color = DockColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
            FavoriteButton("显示器选购", favorite, onToggle)
        }
    }
}

/** 从空间和选购进入同一张卡片，所有操作都使用其已保存的 ID。 */
@Composable
internal fun CardDetail(
    card: CardRecord, cards: List<CardRecord>, actions: PrototypeActions, canStart: Boolean,
    saveMeituanRoute: (String, String?) -> Boolean,
) {
    var configureRoute by remember(card.id) { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)) {
        item {
            FunctionCard(
                card = card,
                onRefresh = { actions.refreshCard(card.id) },
                onCancel = { actions.cancelCard(card.id) },
                onResume = {
                    if (card.run?.message == com.life0423.carddock.platform.FILTER_APPEARANCE_REQUIRED)
                        actions.compareFilterForCard(card.id) else actions.resumeCard(card.id)
                },
                onDelete = { actions.deleteCard(card.id) },
                onPrice = if (card.rule.sourceId == JD_PACKAGE && card.snapshot != null) {
                    { actions.verifyPrice(card.id) }
                } else null,
                canStart = canStart,
                onReplaceRule = cainiaoRuleChange(card, cards, rollback = false)?.let {
                    { actions.changeCardRule(card.id, false) }
                },
                onRollbackRule = cainiaoRuleChange(card, cards, rollback = true)?.let {
                    { actions.changeCardRule(card.id, true) }
                },
            )
        }
        if (card.rule.sourceId == MEITUAN_PACKAGE && card.localTarget != null) item {
            TextButton(onClick = { configureRoute = true }, enabled = canStart && card.run?.phase?.isBusy() != true) {
                Text("设置后台订单路由")
            }
        }
    }
    if (configureRoute) {
        var oid by remember { mutableStateOf("") }
        var invalid by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { configureRoute = false },
            title = { Text("设置后台订单路由") },
            text = {
                OutlinedTextField(oid, { oid = it; invalid = false }, singleLine = true,
                    label = { Text("19 位美团路由号（oid）") },
                    supportingText = { Text(if (invalid) "请输入 19 位数字路由号。" else "路由号与订单号码不同，仅保存在本机。") },
                    isError = invalid, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            },
            confirmButton = {
                TextButton(onClick = {
                    if (saveMeituanRoute(card.id, oid.trim())) configureRoute = false else invalid = true
                }) { Text("保存路由") }
            },
            dismissButton = {
                if (card.localTarget?.routeOid != null) TextButton(onClick = {
                    if (saveMeituanRoute(card.id, null)) configureRoute = false
                }) { Text("清除路由") }
                else TextButton(onClick = { configureRoute = false }) { Text("取消") }
            },
        )
    }
}

/** 当前项结束后才会显示下一项的启动操作。 */
@Composable
internal fun SequenceControls(sequence: SequenceStatus?, actions: PrototypeActions, ids: List<String>) {
    if (sequence == null) {
        OutlinedButton(onClick = { actions.refreshSequence(ids) }, enabled = ids.isNotEmpty()) {
            Text("顺序核验")
        }
    } else {
        Surface(color = DockColors.Surface, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text("${sequence.index}/${sequence.total} · ${sequence.title}", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (sequence.awaitingStart) {
                        FilledTonalButton(onClick = actions.advanceSequence) { Text("开始当前项") }
                    }
                    TextButton(onClick = actions.cancelSequence) { Text("停止顺序核验") }
                }
            }
        }
    }
}
