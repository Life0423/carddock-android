package com.life0423.carddock.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.life0423.carddock.MEITUAN_HISTORY
import com.life0423.carddock.core.model.RunPhase
import com.life0423.carddock.core.model.TemplateDefinition
import com.life0423.carddock.core.session.CreationDraft
import com.life0423.carddock.core.session.PrototypeActions
import com.life0423.carddock.meituanOrderTemplate
import com.life0423.carddock.platform.MeituanOrderTarget
import com.life0423.carddock.ui.theme.DockColors

/**
 * Explicit trial-then-save flow. A changed prompt must be validated again.
 * 按钮能否保存只看阶段、预览和标题。控制器还会拒绝缺少模板、正在写入和超过张数上限。
 * 半展开会把试运行留在折叠线下，所以直接全展开。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CreatorSheet(
    draft: CreationDraft,
    templates: List<TemplateDefinition>,
    actions: PrototypeActions,
    initialMeituan: Boolean = false,
) {
    val busy = draft.phase.isBusy()
    val canSave = draft.phase == RunPhase.SUCCEEDED && draft.preview != null && draft.title.isNotBlank()
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val focusManager = LocalFocusManager.current
    var meituanSelected by rememberSaveable { mutableStateOf(initialMeituan) }
    var route by rememberSaveable { mutableStateOf("") }
    var orderNumber by rememberSaveable { mutableStateOf("") }
    val order = if (meituanSelected) MeituanOrderTarget.parse(route, orderNumber) else null
    ModalBottomSheet(
        onDismissRequest = actions.closeCreator,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = DockColors.Surface,
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = screenHeight * 0.87f).imePadding(),
        ) {
            CreatorHeader(actions.closeCreator)
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .testTag("creator_sheet")
                    .padding(start = 22.dp, end = 22.dp, bottom = 26.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!meituanSelected, {
                        if (meituanSelected) {
                            meituanSelected = false
                            actions.changePrompt("")
                            if (draft.title == MEITUAN_HISTORY) actions.changeTitle("")
                        }
                    }, label = { Text("任务模板") }, enabled = !busy)
                    FilterChip(meituanSelected, {
                        if (!meituanSelected) {
                            meituanSelected = true
                            actions.changePrompt(MEITUAN_HISTORY)
                            if (draft.title == draft.template?.title) actions.changeTitle("")
                        }
                    }, label = { Text(MEITUAN_HISTORY) }, enabled = !busy, modifier = Modifier.testTag("creator_meituan"))
                }
                if (meituanSelected) {
                    Text("美团已完成的外卖订单。试运行会打开美团并核对订单号码。", color = DockColors.Muted,
                        style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(route, { value ->
                        route = value.take(32)
                        actions.changePrompt(MEITUAN_HISTORY)
                    }, enabled = !busy, singleLine = true, label = { Text("路由编号（19 位 oid）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().testTag("meituan_route"))
                    OutlinedTextField(orderNumber, { value ->
                        orderNumber = value.take(48)
                        actions.changePrompt(MEITUAN_HISTORY)
                    }, enabled = !busy, singleLine = true, label = { Text("订单详情中的完整订单号码") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().testTag("meituan_number"))
                } else {
                    OutlinedTextField(
                        value = draft.prompt,
                        onValueChange = actions.changePrompt,
                        enabled = !busy,
                        label = { Text("任务描述") },
                        placeholder = { Text("选用模板，或让已配置的模型匹配") },
                        minLines = 2,
                        maxLines = 4,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().testTag("creator_prompt"),
                    )
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        templates.forEach { template ->
                            AssistChip(
                                onClick = { actions.changePrompt(template.examplePrompt) },
                                label = { Text(if (template.rule.sourceId.startsWith("demo.")) "${template.title} · 演示" else template.title) },
                                enabled = !busy,
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = draft.title,
                    onValueChange = actions.changeTitle,
                    enabled = !busy,
                    label = { Text("卡片名称") },
                    placeholder = { Text("可以在试运行后修改") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().testTag("creator_title"),
                )
                FilledTonalButton(
                    onClick = {
                        focusManager.clearFocus()
                        if (meituanSelected) order?.let { actions.trialPreparedTemplate(meituanOrderTemplate(it)) }
                        else actions.trialDraft()
                    },
                    enabled = !busy && (if (meituanSelected) order != null else draft.prompt.isNotBlank()),
                    modifier = Modifier.fillMaxWidth().testTag("creator_trial"),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.PlayArrow, null, Modifier.size(20.dp))
                    Text(
                        when {
                            busy -> "正在试运行…"
                            draft.phase == RunPhase.NEEDS_USER -> "完成操作后重新试运行"
                            else -> "试运行并预览"
                        },
                        Modifier.padding(start = 8.dp),
                    )
                }
                if (draft.phase == RunPhase.NEEDS_USER) {
                    Text("继续后会重新读取并核验结果。", color = DockColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
                if (draft.message.isNotBlank()) {
                    Text(
                        draft.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = when (draft.phase) {
                            RunPhase.FAILED -> DockColors.Error
                            RunPhase.SUCCEEDED -> DockColors.Mint
                            else -> DockColors.Amber
                        },
                        modifier = Modifier.testTag("creator_message"),
                    )
                }
                draft.preview?.let { preview ->
                    Surface(color = DockColors.Background, shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Rounded.CheckCircle, null, tint = DockColors.Mint, modifier = Modifier.size(20.dp))
                                Text("卡片预览", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            }
                            SnapshotFields(preview.fields)
                            Text("采集于 ${formatTimestamp(preview.fetchedAt)}", style = MaterialTheme.typography.labelSmall)
                            Text(preview.sourceLabel, color = DockColors.Muted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        actions.saveDraft()
                    },
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth().testTag("creator_save"),
                ) {
                    Text("确认，保存到空间")
                }
                Text(
                    if (canSave) "保存后，刷新会直接运行已核验的规则。" else "试运行通过后，才能把卡片加入空间。",
                    color = DockColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun CreatorHeader(onClose: () -> Unit) {
    Row(
        Modifier.padding(start = 22.dp, end = 22.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "创建任务卡",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose, modifier = Modifier.testTag("creator_close")) {
            Icon(Icons.Rounded.Close, contentDescription = "关闭创建；正在试运行时会取消")
        }
    }
}
