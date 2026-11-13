package com.life0423.carddock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.session.PrototypeActions
import com.life0423.carddock.core.session.SequenceStatus
import com.life0423.carddock.platform.JD_PACKAGE
import com.life0423.carddock.ui.theme.DockColors

/** 空间页只展示已保存的卡。空列表不出现“我的功能卡”标题，只留添加入口。 */
@Composable
internal fun SpaceScreen(cards: List<CardRecord>, actions: PrototypeActions, sequence: SequenceStatus? = null) {
    val nativeCards = cards.filterNot { it.rule.sourceId.startsWith("demo.") }
    LazyColumn(
        modifier = Modifier.testTag("space_list"),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("日常空间", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("把常用功能，放在一起。", color = DockColors.Muted, style = MaterialTheme.typography.bodyLarge)
            }
        }
        item { SpaceIntroduction(cards.size, onAdd = { actions.openCreator(null) }) }
        if (nativeCards.isNotEmpty()) item {
            SequenceControls(sequence, actions, nativeCards.map { it.id })
        }
        if (cards.isEmpty()) {
            item { EmptySpace(onAdd = { actions.openCreator(null) }) }
        } else {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("我的功能卡", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${cards.size} 张", color = DockColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            items(cards, key = { it.id }) { card ->
                FunctionCard(
                    card = card,
                    onRefresh = { actions.refreshCard(card.id) },
                    onCancel = { actions.cancelCard(card.id) },
                    onResume = { actions.resumeCard(card.id) },
                    onDelete = { actions.deleteCard(card.id) },
                    onPrice = if (card.rule.sourceId == JD_PACKAGE && card.snapshot != null) {
                        { actions.verifyPrice(card.id) }
                    } else null,
                    canStart = sequence == null,
                )
            }
        }
    }
}

@Composable
private fun SpaceIntroduction(cardCount: Int, onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(
                Brush.linearGradient(listOf(DockColors.Raised, DockColors.Surface)),
                RoundedCornerShape(24.dp),
            )
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (cardCount == 0) "给今天，搭一个顺手的空间" else "常用的信息，都有一个位置",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "从模板试运行，核验通过后保存。",
            style = MaterialTheme.typography.bodyMedium,
            color = DockColors.Muted,
        )
        Button(onClick = onAdd, modifier = Modifier.testTag("open_creator")) {
            Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
            Text("添加功能卡", Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun EmptySpace(onAdd: () -> Unit) {
    Surface(color = DockColors.Surface, shape = RoundedCornerShape(22.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(DockIcons.AutoAwesomeMosaic, null, Modifier.size(36.dp), tint = DockColors.Mint)
            Text("先放进一张功能卡", style = MaterialTheme.typography.titleMedium)
            Text("取件、配送、出行，按你的需要组合。", color = DockColors.Muted)
            Button(onAdd) { Text("选择一个模板") }
        }
    }
}

/** 当前项结束后才会显示下一项的启动操作。 */
@Composable
internal fun SequenceControls(sequence: SequenceStatus?, actions: PrototypeActions, ids: List<String>) {
    if (sequence == null) {
        FilledTonalButton(onClick = { actions.refreshSequence(ids) }, enabled = ids.isNotEmpty()) {
            Text("顺序核验")
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${sequence.index}/${sequence.total} · ${sequence.title}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (sequence.awaitingStart) {
                    FilledTonalButton(onClick = actions.advanceSequence) { Text("开始当前项") }
                }
                TextButton(onClick = actions.cancelSequence) { Text("停止顺序核验") }
            }
        }
    }
}
