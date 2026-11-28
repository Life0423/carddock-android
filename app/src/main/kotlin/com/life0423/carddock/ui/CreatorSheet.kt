package com.life0423.carddock.ui

import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.model.ticketFace

import com.life0423.carddock.isBookQuery
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CreatorScreen(
    draft: CreationDraft,
    templates: List<TemplateDefinition>,
    actions: PrototypeActions,
    modelAvailable: Boolean = false,
    cards: List<CardRecord> = emptyList(),
    chooseAnother: (() -> Unit)? = null,
    listCode: com.life0423.carddock.core.model.PickupEntry? = null,
) = DockActionTheme {
    val context = androidx.compose.ui.platform.LocalContext.current
    var pickingApp by rememberSaveable { mutableStateOf(false) }
    if (pickingApp) AppPicker({ pickingApp = false }, onTask = { demand ->
        pickingApp = false
        context.openFreeTask(demand)
    }) { app ->
        pickingApp = false
        actions.chooseDraftTemplate(app.template(draft.prompt.ifBlank { "打开${app.label}" }))
        actions.trialDraft()
    }
    val busy = draft.phase.isBusy()
    val canSave = draft.phase == RunPhase.SUCCEEDED && draft.preview != null && draft.title.isNotBlank()
    val cart = draft.template?.rule?.officialAction == com.life0423.carddock.core.rule.OfficialAction.ADD_MEAL
    val exploring = draft.template?.rule?.targetKey == com.life0423.carddock.ai.EXPLORE_MEAL
    val editingTemplate = draft.personalTemplateId != null
    val verified = draft.phase == RunPhase.SUCCEEDED && draft.preview != null
    var reviewing by rememberSaveable(draft.personalTemplateId) { mutableStateOf(true) }
    var runningSeen by remember { mutableStateOf(false) }
    LaunchedEffect(draft.phase) {
        if (busy) runningSeen = true
        if (verified && runningSeen) { reviewing = true; runningSeen = false }
    }
    val showFace = verified && reviewing
    val leave: () -> Unit = {
        if (exploring && busy) actions.cancelTrial() else actions.closeCreator()
    }
    BackHandler(enabled = !draft.saving) { if (showFace) reviewing = false else leave() }
    val focusManager = LocalFocusManager.current
    var more by rememberSaveable(draft.personalTemplateId) { mutableStateOf(false) }
    Surface(color = DockColors.Background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            val changingRule = draft.ruleChangeCard != null
            val ruleAction = if (draft.rollingBackRule) "回退菜鸟规则" else "替换菜鸟规则"
            CreatorHeader(leave, when {
                changingRule -> ruleAction
                showFace -> if (editingTemplate) "试运行结果" else "确认添加卡片"
                editingTemplate -> if (draft.template == null) "新建模板" else "编辑模板"
                else -> "配置卡片"
            }, enabled = !draft.saving)
            Column(
                Modifier.weight(1f).verticalScroll(key(showFace) { rememberScrollState() })
                    .testTag("creator_sheet")
                    .padding(start = 22.dp, end = 22.dp, bottom = 26.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (!showFace) FlowSteps(if (editingTemplate) listOf("输入需求", "试运行", "保存模板")
                    else listOf("选模板", "配置试查", "添加到空间"),
                    if (showFace) 2 else if (busy || draft.template != null || !editingTemplate) 1 else 0)
                if (!showFace) {
                val selected = draft.template
                if (selected != null || !editingTemplate) Surface(color = DockColors.Ticket, shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (selected != null) KindIcon(selected.kind, selected.rule, theme = selected.theme)
                        else androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(
                            com.life0423.carddock.R.drawable.ticket_templates_active), null, Modifier.size(44.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (changingRule) ruleAction else if (editingTemplate) "个人模板" else "已选模板",
                                style = MaterialTheme.typography.labelMedium, color = DockColors.Amber)
                            Text(selected?.title ?: "描述你的需求", style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold)
                        }
                        if (chooseAnother != null && !changingRule && !editingTemplate && selected != null && !draft.modelCandidate)
                            TextButton(onClick = chooseAnother, enabled = !busy) { Text("更换") }
                    }
                }
                MonitorControls(draft.template, busy, actions)
                if (selected != null && selected.rule.isBookQuery) {
                    com.life0423.carddock.BookSearch.fromKey(selected.rule.targetKey)?.let { search ->
                        BookParameters(search, !busy) { next ->
                            actions.chooseDraftTemplate(next.template("在淘宝找${next.count}本${next.keyword}，查看推荐理由和链接"))
                        }
                    }
                }
                if (changingRule) Text("${draft.title} · ${selected?.rule?.id.orEmpty()}", color = DockColors.Muted)
                else if (selected != null) Text(selected.description, style = MaterialTheme.typography.bodyMedium, color = DockColors.Muted)
                if (!draft.modelCandidate && !changingRule && (editingTemplate || draft.template == null)) {
                    Text("你想让卡片做什么？", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = draft.prompt,
                        onValueChange = actions.changePrompt,
                        enabled = !busy,
                        placeholder = { Text("例如：在淘宝找两本悬疑小说，给我推荐理由和商品链接。") },
                        minLines = 4,
                        maxLines = 4,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().testTag("creator_prompt").semantics { contentDescription = "你想让卡片做什么？" },
                    )
                }
                if (editingTemplate && draft.template == null) {
                    var browseRules by rememberSaveable(draft.personalTemplateId) { mutableStateOf(false) }
                    TextButton({ browseRules = !browseRules }, enabled = !busy) { Text(if (browseRules) "收起已有规则" else "选择已有规则") }
                    if (browseRules) CapabilityBrowser(busy, templates, choose = actions.chooseDraftTemplate,
                        fill = actions.changePrompt, pickApp = { pickingApp = true })
                }
                if (draft.message.startsWith("应用识别失败") || draft.message.startsWith("请写出要打开的应用名")) {
                    TextButton({ pickingApp = true }) { Text("从本机应用中选择") }
                }
                if (draft.plans.isNotEmpty()) {
                    Text(draft.message, style = MaterialTheme.typography.titleMedium)
                    draft.plans.forEach { plan ->
                        if (plan.rule.officialAction == com.life0423.carddock.core.rule.OfficialAction.OPEN_APP) {
                            if (draft.plans.count { it.title == plan.title } > 1) Text(plan.rule.targetKey)
                            OfficialActionButtons(plan.rule, false, null, null, !busy)
                            OutlinedButton({
                                actions.chooseDraftTemplate(plan)
                                actions.trialDraft()
                            }) { Text("用「${plan.title.removePrefix("打开")}」创建模板") }
                            return@forEach
                        }
                        OutlinedButton(onClick = {
                            actions.chooseDraftTemplate(plan)
                            if (plan.rule.targetKey == com.life0423.carddock.ai.EXPLORE_MEAL || plan.rule.officialAction == com.life0423.carddock.core.rule.OfficialAction.ADD_MEAL) actions.trialDraft()
                        }, enabled = !busy) {
                            Text(if (plan.rule.sourceId == com.life0423.carddock.platform.APPLE_STORE &&
                                plan.rule.targetKey != com.life0423.carddock.platform.PRICE_RANK) plan.title + "\n" + plan.description.substringBefore("。")
                                else when (plan.rule.officialAction) {
                                    com.life0423.carddock.core.rule.OfficialAction.ADD_MEAL -> "查找套餐并配置加购"
                                    com.life0423.carddock.core.rule.OfficialAction.SELECT_MEAL -> "每次自己选"
                                    com.life0423.carddock.core.rule.OfficialAction.REPEAT_MEAL -> "记住常用搭配"
                                    else -> if (plan.rule.targetKey == com.life0423.carddock.ai.EXPLORE_MEAL) "在麦当劳试查" else plan.title
                                })
                        }
                    }
                }
                draft.demonstrations.forEachIndexed { index, candidate ->
                    FilledTonalButton(onClick = { actions.demonstrateField(index) }, enabled = !busy) {
                        val name = candidate.template.rule.countRoute?.subject.orEmpty()
                        Text(candidate.template.pickupEntry?.label ?: ("第${index + 1}项 · $name · " + candidate.observed.values.joinToString()))
                    }
                }
                val menu = draft.mealMenu
                if (menu != null && selected != null && draft.phase == RunPhase.NEEDS_USER) {
                    if (menu.products.isNotEmpty()) Text(draft.message, style = MaterialTheme.typography.titleMedium)
                    if (menu.products.isNotEmpty()) menu.products.forEach { name ->
                        OutlinedButton(onClick = {
                            val meal = requireNotNull(selected.rule.meal).copy(product = name, store = menu.store, searchByDescription = false)
                            val next = if (cart) com.life0423.carddock.ai.cartPlan(draft.prompt, meal) else com.life0423.carddock.ai.explorationPlan(draft.prompt, meal)
                            actions.chooseDraftTemplate(next.choices.single())
                            actions.trialDraft()
                        }) { Text(name) }
                    } else FixedMealPicker(menu, selected, actions)
                }
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
                if (!changingRule && (!editingTemplate || selected != null || draft.title.isNotBlank())) OutlinedTextField(
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
                    ThemePicker(checkNotNull(draft.template), !busy, actions.changeTheme)
                    if (more) DisplayFieldPicker(checkNotNull(draft.template), !busy, actions.changeDisplayFields)
                }
                if (editingTemplate && more) OutlinedTextField(
                    value = draft.description, onValueChange = actions.changeTemplateDescription,
                    enabled = !busy, label = { Text("模板说明") },
                    supportingText = { Text("描述用途，请勿填写个人订单信息。") },
                    modifier = Modifier.fillMaxWidth().testTag("creator_description"),
                )
                if (editingTemplate && draft.template?.rule?.sourceId == com.life0423.carddock.ai.MCD_PACKAGE) {
                    TextButton(enabled = !busy, onClick = { actions.changePrompt(draft.prompt); actions.prepareTemplate() }) { Text("更换执行方式") }
                }
                if (editingTemplate && draft.template == null && modelAvailable && draft.plans.isEmpty())
                    Text("需要模型理解时，会将需求发送给已配置的模型。请勿填写个人信息。", style = MaterialTheme.typography.bodySmall)
                if (verified && !reviewing) Button(
                    onClick = { reviewing = true }, modifier = Modifier.fillMaxWidth(),
                ) { Text("查看卡面预览") }
                if (busy && (exploring || draft.template?.rule?.isBookQuery == true)) TextButton(onClick = actions.cancelTrial) { Text("取消试查") }
                if (draft.phase == RunPhase.NEEDS_USER) {
                    if (draft.missingCapability != null) PhoneRecoveryButton(draft.missingCapability)
                    else Text("继续后会重新读取并核验结果。", color = DockColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
                if (draft.message == com.life0423.carddock.platform.FILTER_APPEARANCE_REQUIRED && !busy) {
                    OutlinedButton(onClick = actions.compareFilterForTrial) { Text("允许本次在本机比对筛选项并重试") }
                }
                if (draft.plans.isEmpty() && draft.message.isNotBlank() && !(exploring && menu != null)) {
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
                }
                if (showFace) draft.preview?.let { preview ->
                    val template = checkNotNull(draft.template)
                    val face = preview.ticketFace(template.display, template.rule)
                    val fields = face.fields
                    Surface(color = ticketPaper(face.role), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                KindIcon(template.kind, template.rule, theme = template.theme)
                                Column { Text(if (template.rule.isBookQuery) "后台查询结果" else "核验通过", color = DockColors.Sage, style = MaterialTheme.typography.labelMedium)
                                    Text(draft.title.ifBlank { template.title }, style = MaterialTheme.typography.titleLarge) }
                            }
                            HorizontalDivider(color = DockColors.Amber.copy(alpha = .3f))
                            if (template.rule.isBookQuery) BookFields(fields)
                            else if (template.rule.meal != null && fields.any { it.key == "options" }) {
                                val mealReading = fields.firstOrNull { it.key == "pair" } ?: fields.first { it.key == "options" }
                                if (exploring) {
                                    fields.firstOrNull { it.key == "entry" }?.let { Text("官方商品 · ${it.value}") }
                                    Text("读取时间 · " + formatTimestamp(preview.fetchedAt))
                                    fields.firstOrNull { it.key == "matches" }?.let { Text("配餐核对\n${it.value}") }
                                }
                                Text(if (cart) "已选搭配" else if (exploring) "本次可见分组" else if (mealReading.key == "pair") "固定搭配" else "规格", style = MaterialTheme.typography.labelMedium)
                                Text(mealReading.value, style = MaterialTheme.typography.titleLarge)
                                fields.firstOrNull { it.key == "store" }?.let { Text("门店 · ${it.value}") }
                            } else if (template.rule.monitorComparison != null || template.rule.targetKey == com.life0423.carddock.platform.PRICE_RANK) {
                                fields.forEach { field ->
                                    Column {
                                        Text(field.label, color = DockColors.Muted, style = MaterialTheme.typography.labelMedium)
                                        Text(field.value, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            } else {
                                TicketResult(face)
                                if (fields.size > 2) SnapshotFields(fields.drop(2))
                            }
                            if (template.rule.sourceId == com.life0423.carddock.platform.APPLE_STORE &&
                                template.rule.targetKey != com.life0423.carddock.platform.PRICE_RANK) {
                                Text("加入空间后每天核价，标价下降时提醒。", style = MaterialTheme.typography.bodySmall)
                                PriceWatchControls()
                            }
                            if (template.opensPickupList) {
                                Text("进入菜鸟后选择包裹")
                            }
                            OfficialActionButtons(template.rule, template.opensPickupList, template.pickupEntry, listCode, !busy)
                            template.rule.localReminder?.let { ReminderOptions(it) }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(preview.sourceLabel, color = DockColors.Muted, style = MaterialTheme.typography.labelSmall)
                        var evidence by remember { mutableStateOf(false) }
                        TextButton({ evidence = !evidence }) { Text(if (evidence) "收起核验依据" else "查看核验依据") }
                        if (evidence) Text("核验时间 · " + formatTimestamp(preview.fetchedAt) + "\n" +
                                fields.joinToString("\n") { "${it.label} · ${it.value}" },style = MaterialTheme.typography.bodyMedium)
                    }
                    if (editingTemplate && !exploring) ThemePicker(template, !busy, actions.changeTheme)
                    if (!exploring && !changingRule && fields.size > 1) {
                        Text("卡面主字段", style = MaterialTheme.typography.titleSmall)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            fields.forEach { field ->
                                androidx.compose.material3.FilterChip(selected = field == fields.first(), onClick = {
                                    if (field != fields.first()) actions.changeDisplayFields(face.primaryFirst(field.key))
                                }, label = { Text(field.label) })
                            }
                        }
                    }
                    if (draft.message.isNotBlank() && draft.saving.not() && !draft.message.startsWith("核验通过"))
                        Text(draft.message, color = DockColors.Muted, modifier = Modifier.testTag("creator_message"))
                }
                if (!exploring && (!editingTemplate || showFace)) Text(
                    when {
                        canSave && draft.template?.rule?.officialAction == com.life0423.carddock.core.rule.OfficialAction.SELECT_MEAL -> "建卡后点「去选商品」可重新核验门店和规格。"
                        canSave -> "确认后，刷新会直接运行已核验的规则。"
                        changingRule -> "试运行通过后可确认${if (draft.rollingBackRule) "回退" else "替换"}。"
                        else -> "试运行通过后可添加到空间。"
                    },
                    color = DockColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showFace) TextButton(modifier = Modifier.weight(1f), onClick = { reviewing = false }) { Text(if (editingTemplate) "返回修改模板" else "返回修改配置") }
                if (!showFace && editingTemplate) TextButton(onClick = actions.keepTemplateDraft,
                    enabled = !busy && (draft.title.isNotBlank() || draft.prompt.isNotBlank()),
                    modifier = Modifier.testTag("save_template_edit")) { Text("保存草稿") }
                if (!showFace && editingTemplate && draft.template == null) Button(
                    onClick = { focusManager.clearFocus(); context.startRouteTrial(draft.prompt) },
                    enabled = draft.prompt.trim().length in 1..com.wangzan101.mika.core.remote.RemoteTaskService.MAX_GOAL && !busy,
                    shape = DockButtonShape, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("free_template"),
                ) { Text("让 Mika 试查") }
                if (showFace && !exploring) Button(
                    onClick = {
                        focusManager.clearFocus()
                        actions.saveDraft()
                    },
                    enabled = canSave && !draft.saving,
                    shape = DockButtonShape, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("creator_save"),
                ) {
                    Text(when {
                        changingRule -> "确认$ruleAction"
                        editingTemplate -> "保存模板"
                        else -> "添加到空间"
                    })
                }
                if (!showFace && (!editingTemplate || draft.template != null)) Button(
                    onClick = {
                        focusManager.clearFocus()
                        actions.trialDraft()
                    },
                    enabled = draft.prompt.isNotBlank() && !busy &&
                        (draft.template != null || !draft.modelCandidate && !editingTemplate),
                    shape = DockButtonShape, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("creator_trial"),
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

            }
        }
    }
}

/** 只显示本次实读的分组。选择作用于卡坞模板，重新试运行才赋予保存资格。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FixedMealPicker(menu: com.life0423.carddock.core.rule.MealMenu, template: TemplateDefinition, actions: PrototypeActions) {
    val meal = template.rule.meal ?: return
    if (menu.groups.isEmpty()) {
        TextButton(onClick = {
            val pinned = com.life0423.carddock.ai.fixedMealTemplate(template.examplePrompt, meal.copy(store = menu.store))
            actions.chooseDraftTemplate(template.copy(rule = pinned.rule, description = pinned.description))
        }) { Text("固定到${menu.store}") }
        return
    }
    var choices by remember(menu, template) { mutableStateOf((menu.defaults + meal.choices).filter { (group, option) -> option in menu.groups[group].orEmpty() }) }
    Text(menu.store, style = MaterialTheme.typography.titleMedium)
    for ((group, options) in menu.groups) {
        Text(group)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (option in options) androidx.compose.material3.FilterChip(selected = choices[group] == option,
                onClick = { choices = choices + (group to option) }, label = { Text(option) })
        }
    }
    Button(enabled = choices.keys == menu.groups.keys, onClick = {
        val chosen = meal.copy(store = menu.store, choices = choices)
        val verifiedRule = if (template.rule.officialAction == com.life0423.carddock.core.rule.OfficialAction.ADD_MEAL) com.life0423.carddock.ai.cartPlan(template.examplePrompt, chosen).choices.single()
            else com.life0423.carddock.ai.fixedMealTemplate(template.examplePrompt, chosen)
        actions.chooseDraftTemplate(template.copy(rule = verifiedRule.rule, description = verifiedRule.description))
        actions.trialDraft()
    }) { Text("核验这组搭配") }
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


/** 同一套步骤票签用于建卡、建模板和公开发布。 */
@Composable
internal fun FlowSteps(labels: List<String>, active: Int) {
    Column {
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(18.dp)) {
            val cell = size.width / labels.size
            val y = size.height / 2
            drawLine(DockColors.Outline, androidx.compose.ui.geometry.Offset(cell / 2, y),
                androidx.compose.ui.geometry.Offset(size.width - cell / 2, y), 1.dp.toPx())
            labels.indices.forEach { index ->
                val point = androidx.compose.ui.geometry.Offset(cell * (index + .5f), y)
                drawCircle(if (index <= active) DockColors.Mint else DockColors.Muted, 5.dp.toPx(), point)
                if (index > active) drawCircle(DockColors.Background, 3.5.dp.toPx(), point)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { index, label ->
                Text(label, Modifier.weight(1f), color = if (index == active) DockColors.Mint else DockColors.Muted,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** 在空间建卡时也允许换用途和预算，仅改本次候选并作废旧预览。 */
@Composable
private fun MonitorControls(template: TemplateDefinition?, busy: Boolean, actions: PrototypeActions) {
    val plan = template?.rule?.monitorComparison ?: return
    fun change(next: com.life0423.carddock.core.rule.MonitorComparison) {
        actions.chooseDraftTemplate(com.life0423.carddock.updateMonitorPlan(template, next))
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        com.life0423.carddock.core.rule.MonitorUse.entries.forEach { use ->
            androidx.compose.material3.FilterChip(selected = use == plan.use,
                onClick = { if (use != plan.use) change(plan.copy(use = use, focus = null)) }, enabled = !busy, label = { Text(use.label) })
        }
    }
    OutlinedTextField(value = plan.budgetYuan?.toString().orEmpty(), onValueChange = { input ->
        if (input.isEmpty()) change(plan.copy(budgetYuan = null))
        else input.toIntOrNull()?.takeIf { it > 0 }?.let { change(plan.copy(budgetYuan = it)) }
    }, enabled = !busy, modifier = Modifier.fillMaxWidth(), singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text("预算上限（元，选填）") })
}
