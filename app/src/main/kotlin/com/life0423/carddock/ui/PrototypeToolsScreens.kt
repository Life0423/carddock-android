package com.life0423.carddock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.life0423.carddock.PrototypeTools
import com.life0423.carddock.core.model.AppPrice
import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.session.PrototypeActions
import com.life0423.carddock.core.session.SequenceStatus
import com.life0423.carddock.currentShoppingItem
import com.life0423.carddock.data.ShoppingItem
import com.life0423.carddock.data.comparablePrices
import com.life0423.carddock.data.priceChange
import com.life0423.carddock.platform.JdProduct
import com.life0423.carddock.shoppingCard
import com.life0423.carddock.ui.theme.DockColors

/** 密钥输入仅存在于当前表单，配置摘要只显示地址和模型。 */
@Composable
internal fun ModelSettingsForm(tools: PrototypeTools, save: (String, String, String) -> Unit, clear: () -> Unit) {
    var endpoint by remember(tools.model) { mutableStateOf(tools.model?.endpoint.orEmpty()) }
    var model by remember(tools.model) { mutableStateOf(tools.model?.model.orEmpty()) }
    var key by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("模型设置", style = MaterialTheme.typography.titleMedium)
        if (tools.model != null) Text("已配置 · " + tools.model.model)
        OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth(), label = { Text("HTTPS 基础地址（含 /v1 等路径）") },
            singleLine = true, enabled = !tools.modelBusy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("模型名") },
            singleLine = true, enabled = !tools.modelBusy)
        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("API 密钥") },
            singleLine = true, enabled = !tools.modelBusy, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        Row {
            Button(onClick = { save(endpoint, model, key); key = "" },
                enabled = !tools.modelBusy && endpoint.isNotBlank() && model.isNotBlank() && key.isNotBlank()) {
                Text("保存配置")
            }
            TextButton(onClick = { clear(); key = "" }, enabled = !tools.modelBusy) { Text("清除配置") }
        }
        tools.modelMessage?.let { Text(it) }
    }
}

/** 当前卡片和保存快照独立展示，重核身份不会刷新展示价。 */
@Composable
internal fun ShoppingScreen(
    cards: List<CardRecord>, sequence: SequenceStatus?, tools: PrototypeTools,
    actions: PrototypeActions, save: (String) -> Unit,
) {
    val saved = tools.shopping
    var note by remember(saved) { mutableStateOf(saved?.note.orEmpty()) }
    val a = currentShoppingItem(cards, JdProduct.A)
    val b = currentShoppingItem(cards, JdProduct.B)
    val cardA = shoppingCard(cards, JdProduct.A)
    val cardB = shoppingCard(cards, JdProduct.B)
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Text("京东选购", style = MaterialTheme.typography.headlineMedium) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (a?.priceVerificationFailed == false && b?.priceVerificationFailed == false &&
                        comparablePrices(a.price, b.price)
                    ) "展示价条件一致" else "展示价不可直接比较",
                    color = DockColors.Muted,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShoppingColumn("商品 A", a, saved?.a, Modifier.weight(1f))
                    ShoppingColumn("商品 B", b, saved?.b, Modifier.weight(1f))
                }
            }
        }
        item {
            if (sequence != null) SequenceControls(sequence, actions, emptyList())
            else Button(
                onClick = { actions.refreshSequence(listOfNotNull(cardA?.id, cardB?.id)) },
                enabled = cardA != null && cardB != null,
            ) { Text("重新核验 A/B 身份") }
        }
        item {
            if (cardA == null || cardB == null) Text("在模板页创建京东 A、B 卡后可重新核验。")
            OutlinedTextField(note, { note = it.take(500) }, Modifier.fillMaxWidth(), label = { Text("选购备注") })
            Button(onClick = { save(note) }, enabled = !tools.shoppingBusy && a != null && b != null && sequence == null) {
                Text("保存选购任务")
            }
            tools.shoppingMessage?.let { Text(it) }
        }
    }
}

@Composable
private fun ShoppingColumn(label: String, current: ShoppingItem?, saved: ShoppingItem?, modifier: Modifier) {
    Surface(modifier, color = DockColors.Surface, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            if (current == null) Text("当前身份无法核验")
            current?.let { ShoppingFields(it) }
            saved?.let {
                Text("已存快照", style = MaterialTheme.typography.titleSmall)
                ShoppingFields(it)
                Text("价格变化 · " + priceChange(current, it))
            }
        }
    }
}

@Composable
private fun ShoppingFields(item: ShoppingItem) {
    Text(item.title)
    Text(item.sku)
    Text(item.variant)
    Text("身份 " + formatTimestamp(item.identity.fetchedAt))
    PriceEvidence(item.price)
    if (item.priceVerificationFailed && item.price != null) Text("上次有效价格 · 本次未核验")
}

@Composable
internal fun PriceEvidence(price: AppPrice?) {
    if (price == null) Text("App 展示价待核验", color = DockColors.Muted)
    else {
        Text(price.amount + " " + price.currency + " · " + price.label)
        Text(price.discount ?: "可见优惠条件未知", color = DockColors.Muted)
        Text("App 展示价 " + formatTimestamp(price.verifiedAt), style = MaterialTheme.typography.labelSmall)
    }
}
