package com.life0423.carddock.ui

import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.model.displayFields

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.life0423.carddock.core.model.DisplayField
import com.life0423.carddock.core.rule.RuleStep
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.life0423.carddock.core.model.RunPhase
import com.life0423.carddock.core.model.TemplateDefinition
import com.life0423.carddock.core.session.CreationDraft
import com.life0423.carddock.core.session.PrototypeActions
import com.life0423.carddock.ui.theme.DockColors

/**
 * Explicit trial-then-save flow. A changed prompt must be validated again.
 * 按钮能否保存只看阶段、预览和标题。控制器还会拒绝缺少模板、正在写入和超过张数上限。
 * 半展开会把试运行留在折叠线下，所以直接全展开。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CreatorScreen(
    draft: CreationDraft,
    templates: List<TemplateDefinition>,
    actions: PrototypeActions,
    modelAvailable: Boolean = false,
    cards: List<CardRecord> = emptyList(),
) {
    val busy = draft.phase.isBusy()
    val canSave = draft.phase == RunPhase.SUCCEEDED && draft.preview != null && draft.title.isNotBlank()
    val editingTemplate = draft.personalTemplateId != null
    BackHandler { actions.closeCreator() }
    val focusManager = LocalFocusManager.current
    var examples by rememberSaveable(draft.personalTemplateId) { mutableStateOf(false) }
    var more by rememberSaveable(draft.personalTemplateId) { mutableStateOf(false) }
    Surface(color = DockColors.Background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            val changingRule = draft.ruleChangeCard != null
            val ruleAction = if (draft.rollingBackRule) "回退菜鸟规则" else "替换菜鸟规则"
            CreatorHeader(actions.closeCreator, when {
                changingRule -> ruleAction
                editingTemplate -> "编辑模板"
                else -> "配置卡片"
            }, enabled = !draft.saving)
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .testTag("creator_sheet")
                    .padding(start = 22.dp, end = 22.dp, bottom = 26.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    if (changingRule) "${draft.title} · ${draft.template?.rule?.id.orEmpty()}。试运行通过后确认。"
                    else draft.template?.description ?: "描述要查询的内容，或从已有能力开始。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DockColors.Muted,
                )
                if (!draft.modelCandidate && !changingRule && (editingTemplate || draft.template == null)) OutlinedTextField(
                    value = draft.prompt,
                    onValueChange = actions.changePrompt,
                    enabled = !busy,
                    label = { Text("查询需求") },
                    placeholder = { Text("例如：只显示美团订单状态") },
                    minLines = 2,
                    maxLines = 4,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().testTag("creator_prompt"),
                )
                if (editingTemplate && draft.template == null) TextButton(onClick = { examples = !examples }) {
                    Text(if (examples) "收起示例" else "查看支持的需求示例")
                }
                if (editingTemplate && draft.template == null && examples) Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    templates.filterNot { it.rule.sourceId.startsWith("demo.") }.forEach { template ->
                        AssistChip(
                            onClick = { actions.chooseDraftTemplate(template) },
                            label = { Text(template.title) },
                            enabled = !busy,
                        )
                    }
                }
                draft.demonstrations.forEachIndexed { index, candidate ->
                    FilledTonalButton(onClick = { actions.demonstrateField(index) }, enabled = !busy) {
                        val name = candidate.template.rule.countRoute?.subject.orEmpty()
                        Text(candidate.template.pickupEntry?.label ?: ("第${index + 1}项 · $name · " + candidate.observed.values.joinToString()))
                    }
                }
                if (editingTemplate && draft.template == null) FilledTonalButton(
                    onClick = { focusManager.clearFocus(); actions.prepareTemplate() },
                    enabled = draft.prompt.isNotBlank() && !busy,
                    modifier = Modifier.testTag("prepare_template"),
                ) { Text("生成候选") }
                if (draft.template?.requiresOrderNumber == true || templates.any {
                    it.requiresOrderNumber && it.examplePrompt.equals(draft.prompt.trim(), ignoreCase = true)
                }) {
                    val targets = cards.filter { it.localTarget != null && it.snapshot != null &&
                        it.rule.sourceId == com.life0423.carddock.platform.MEITUAN_PACKAGE }
                        .sortedByDescending { it.localTarget?.routeOid != null }.distinctBy { it.localTarget?.orderNumber }
                    var manual by remember(draft.personalTemplateId, draft.template?.id) { mutableStateOf(false) }
                    if (targets.isNotEmpty()) {
                        Text("选择已核验的本机订单", style = MaterialTheme.typography.titleSmall)
                        targets.forEach { card ->
                            val target = checkNotNull(card.localTarget)
                            AssistChip(onClick = {
                                actions.changeOrderNumber(target.orderNumber)
                                actions.changeRouteOid(target.routeOid.orEmpty())
                                manual = false
                            }, enabled = !busy, label = {
                                val time = card.snapshot?.fields?.firstOrNull { it.key == "orderTime" }?.value
                                Text((if (draft.orderNumber == target.orderNumber) "已选择 · " else "") +
                                    "${card.title} · ${time ?: "尾号 ${target.orderNumber.takeLast(4)}"}")
                            })
                        }
                    }
                    TextButton(onClick = { manual = !manual }, enabled = !busy) {
                        Text(if (manual) "收起订单设置" else if (targets.isEmpty()) "指定历史订单" else "指定其他历史订单")
                    }
                    if (manual) {
                        OutlinedTextField(
                            value = draft.orderNumber, onValueChange = actions.changeOrderNumber,
                            label = { Text("本机订单号码") }, enabled = !busy, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag("creator_order"),
                        )
                        OutlinedTextField(
                            value = draft.routeOid, onValueChange = actions.changeRouteOid,
                            label = { Text("后台路由号（选填）") }, enabled = !busy, singleLine = true,
                            supportingText = { Text("填写 19 位路由号可后台核验。留空按提示手动核验。") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth().testTag("creator_route"),
                        )
                    }
                }
                if (draft.modelCandidate || modelAvailable && draft.template?.pickupEntry == null && draft.template?.rule?.targetKey == "cainiao.pickup.empty" && !changingRule) {
                    FilledTonalButton(onClick = actions.generateCandidate, enabled = !busy) {
                        Text("生成菜鸟候选")
                    }
                    if (draft.modelCandidate) draft.template?.rule?.let { rule ->
                        Text(rule.id + " · 菜鸟待取空状态")
                        Text("核对取包裹页面 → 读取待取状态 → 检查状态非空")
                    }
                }
                if (!changingRule && (!editingTemplate || draft.template != null || draft.title.isNotBlank())) OutlinedTextField(
                    value = draft.title,
                    onValueChange = actions.changeTitle,
                    enabled = !busy,
                    label = { Text(if (editingTemplate) "模板名称" else "卡片名称") },
                    placeholder = { Text("可以在试运行后修改") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().testTag("creator_title"),
                )
                if (editingTemplate && draft.template != null) {
                    TextButton(onClick = { more = !more }) { Text(if (more) "收起自定义" else "自定义展示与说明") }
                    if (more) DisplayFieldPicker(checkNotNull(draft.template), !busy, actions.changeDisplayFields)
                }
                if (editingTemplate && more) OutlinedTextField(
                    value = draft.description, onValueChange = actions.changeTemplateDescription,
                    enabled = !busy, label = { Text("模板说明") },
                    supportingText = { Text("描述用途，请勿填写个人订单信息。") },
                    modifier = Modifier.fillMaxWidth().testTag("creator_description"),
                )
                if (!editingTemplate || draft.template != null) FilledTonalButton(
                    onClick = {
                        focusManager.clearFocus()
                        actions.trialDraft()
                    },
                    enabled = draft.prompt.isNotBlank() && !busy &&
                        (draft.template != null || !draft.modelCandidate && !editingTemplate),
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
                if (draft.message == com.life0423.carddock.platform.FILTER_APPEARANCE_REQUIRED && !busy) {
                    OutlinedButton(onClick = actions.compareFilterForTrial) { Text("允许本次在本机比对筛选项并重试") }
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
                            SnapshotFields(preview.displayFields(draft.template?.display.orEmpty()))
                            draft.template?.pickupEntry?.let { PickupCodeButton(it, enabled = !busy) }
                            Text(preview.sourceLabel + " · " + formatTimestamp(preview.fetchedAt), color = DockColors.Muted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (editingTemplate) TextButton(
                    onClick = actions.keepTemplateDraft,
                    enabled = !busy && (draft.title.isNotBlank() || draft.prompt.isNotBlank()),
                    modifier = Modifier.testTag("save_template_edit"),
                ) { Text("保存草稿") }
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        actions.saveDraft()
                    },
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth().testTag("creator_save"),
                ) {
                    Text(when {
                        changingRule -> "确认$ruleAction"
                        editingTemplate -> "保存模板"
                        else -> "添加到空间"
                    })
                }
                Text(
                    when {
                        canSave -> "确认后，刷新会直接运行已核验的规则。"
                        changingRule -> "试运行通过后可确认${if (draft.rollingBackRule) "回退" else "替换"}。"
                        editingTemplate -> "先选定或核验查询规则，可保存草稿。"
                        else -> "试运行通过后可添加到空间。"
                    },
                    color = DockColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** 在同一个对话框编辑字段名、可见性与顺序，取消会保留上一次配置。 */
@Composable
private fun DisplayFieldPicker(template: TemplateDefinition, enabled: Boolean, apply: (List<DisplayField>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val options = template.rule.steps.filterIsInstance<RuleStep.ReadField>().map { DisplayField(it.outputKey, it.label) }
    val chosen = template.display.ifEmpty { options }
    TextButton(onClick = { open = true }, enabled = enabled) { Text("展示字段 · ${chosen.joinToString("、") { it.label }}") }
    if (!open) return
    var fields by remember { mutableStateOf(chosen) }
    AlertDialog(
        onDismissRequest = { open = false }, title = { Text("展示字段") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                (fields + options.filter { option -> fields.none { it.key == option.key } }).forEach { field ->
                    val index = fields.indexOfFirst { it.key == field.key }
                    val original = options.first { it.key == field.key }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(index >= 0, onCheckedChange = { selected ->
                            fields = if (selected) fields + original else fields.filterNot { it.key == field.key }
                        })
                        if (index < 0) Text(original.label) else {
                            OutlinedTextField(field.label, { label ->
                                fields = fields.map { if (it.key == field.key && label.length <= 16) it.copy(label = label) else it }
                            }, label = { Text(original.label) }, singleLine = true, modifier = Modifier.weight(1f))
                            if (index > 0) TextButton(onClick = {
                                fields = fields.toMutableList().apply { add(index - 1, removeAt(index)) }
                            }) { Text("上移") }
                        }
                    }
                }
            }
        },
        dismissButton = { TextButton({ open = false }) { Text("取消") } },
        confirmButton = {
            TextButton(onClick = {
                apply(fields.map { it.copy(label = it.label.trim()) })
                open = false
            }, enabled = fields.isNotEmpty() && fields.none { it.label.isBlank() }) { Text("应用") }
        },
    )
}

@Composable
private fun CreatorHeader(onClose: () -> Unit, title: String, enabled: Boolean) {
    Row(
        Modifier.padding(start = 22.dp, end = 22.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose, enabled = enabled, modifier = Modifier.testTag("creator_close")) {
            Icon(Icons.Rounded.Close, contentDescription = "关闭创建；正在试运行时会取消")
        }
    }
}
