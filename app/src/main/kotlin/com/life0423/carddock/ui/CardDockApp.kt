package com.life0423.carddock.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.life0423.carddock.PrototypeTools
import com.life0423.carddock.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.life0423.carddock.data.SquareState
import com.life0423.carddock.data.SquareStore
import com.life0423.carddock.platform.JdProduct
import com.life0423.carddock.shoppingCard
import com.life0423.carddock.core.session.PrototypeActions
import com.life0423.carddock.core.session.PrototypeState
import com.life0423.carddock.ui.theme.CardDockTheme
import com.life0423.carddock.ui.theme.DockColors

/** 管理四个主入口与空间内的导航，设备操作仍由会话回调执行。 */
@Composable
internal fun CardDockApp(
    state: PrototypeState,
    actions: PrototypeActions,
    openAccessibilitySettings: () -> Unit = {},
    tools: PrototypeTools? = null,
    squareStore: SquareStore? = null,
    saveModel: (String, String, String) -> Unit = { _, _, _ -> },
    clearModel: () -> Unit = {},
    saveShopping: (String) -> Unit = {},
    pageReaderConnected: Boolean = false,
    mikaConnected: Boolean = false,
    saveMeituanRoute: (String, String?) -> Boolean = { _, _ -> false },
    toggleShoppingFavorite: () -> Unit = {},
) {
    val square = squareStore?.state?.collectAsStateWithLifecycle()?.value ?: SquareState()
    CardDockTheme {
        var tab by rememberSaveable { mutableStateOf("space") }
        var accountReturn by rememberSaveable { mutableStateOf<String?>(null) }
        val requestAccount: () -> Unit = { accountReturn = tab; tab = "profile" }
        var publishPicker by rememberSaveable { mutableStateOf(false) }
        var spaceMenu by remember { mutableStateOf(false) }
        var shopping by rememberSaveable { mutableStateOf(false) }
        var pickingTemplate by rememberSaveable { mutableStateOf(false) }
        var editorFromPicker by rememberSaveable { mutableStateOf(false) }
        var newTemplateId by rememberSaveable { mutableStateOf<String?>(null) }
        var returnToSpaceAfterSave by rememberSaveable { mutableStateOf(false) }
        var detailId by rememberSaveable { mutableStateOf<String?>(null) }
        val shoppingState = rememberSaveableStateHolder()
        // 旧取件卡的附加快捷方式指向一份具体的、已发布的本机模板。
        val pickupTemplate = state.personalTemplates.mapNotNull { it.published }.filter {
            it.pickupEntry != null && it.rule.sourceId == com.life0423.carddock.platform.CAINIAO_PACKAGE
        }.singleOrNull()
        val detail = state.cards.firstOrNull { it.id == detailId }
        val pair = JdProduct.entries.mapNotNull { shoppingCard(state.cards, it)?.id }
        val shoppingAvailable = tools != null && (tools.shopping != null || pair.size == 2)
        val snackbarHost = remember { SnackbarHostState() }
        LaunchedEffect(state.notice) {
            state.notice?.let {
                snackbarHost.showSnackbar(it)
                actions.dismissNotice()
            }
        }
        LaunchedEffect(state.draft) {
            if (state.draft == null && returnToSpaceAfterSave) {
                returnToSpaceAfterSave = false
                tab = "space"
                shopping = false
                detailId = null
            }
        }
        LaunchedEffect(state.draft, editorFromPicker) {
            if (state.draft == null && editorFromPicker) {
                editorFromPicker = false
                pickingTemplate = true
            }
        }
        BackHandler(state.draft == null && tab == "space" && (detail != null || shopping)) {
            if (detail != null) detailId = null else shopping = false
        }
        val draft = state.draft
        if (draft == null) Scaffold(
            containerColor = DockColors.Background,
            topBar = {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 60.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (tab == "space" && (detail != null || shopping)) {
                        IconButton(onClick = { if (detail != null) detailId = null else shopping = false }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回空间")
                        }
                    }
                    Text(
                        when (tab) {
                            "templates" -> "模板"
                            "square" -> "广场"
                            "profile" -> "我的"
                            else -> when {
                                detail != null -> "卡片详情"
                                shopping -> "显示器选购"
                                else -> "卡片空间"
                            }
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineSmall, fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (tab == "space" && detail == null && !shopping) {
                        val ids = state.cards.filterNot { it.rule.sourceId.startsWith("demo.") }.map { it.id }
                        if (ids.isNotEmpty() && state.sequence == null) Box {
                            IconButton(onClick = { spaceMenu = true }) { Icon(DockIcons.MoreHoriz, "空间操作") }
                            DropdownMenu(spaceMenu, { spaceMenu = false }) {
                                DropdownMenuItem(text = { Text("顺序核验") }, onClick = {
                                    spaceMenu = false
                                    actions.refreshSequence(ids)
                                })
                            }
                        }
                        FilledIconButton(
                            onClick = { pickingTemplate = true },
                            enabled = !state.loading && state.sequence == null,
                            modifier = Modifier.testTag("open_creator"),
                        ) { Icon(Icons.Rounded.Add, "添加功能卡") }
                    } else if (tab == "templates") {
                        FilledTonalButton({ actions.openTemplateEditor(null) }, Modifier.testTag("new_template"),
                            enabled = state.sequence == null && !state.loading,
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = DockColors.Mint,
                                contentColor = MaterialTheme.colorScheme.onPrimary)) {
                            Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                            Text("新建")
                        }
                    } else if (tab == "square" && square.available) {
                        TextButton({ if (square.signedIn) publishPicker = true else requestAccount() },
                            enabled = !square.busy) { Text("发布") }
                    }
                }
            },
            bottomBar = {
                DockNavigation(tab) {
                    if (it != "profile") accountReturn = null
                    tab = it
                    detailId = null
                    shopping = false
                }
            },
            snackbarHost = { SnackbarHost(snackbarHost) },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when {
                    state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    tab == "templates" -> TemplatesScreen(
                        state.personalTemplates, square, enabled = state.sequence == null,
                        onDownload = { if (square.signedIn) squareStore?.download(it) else requestAccount() },
                        onUnfavorite = { squareStore?.toggleFavorite(it) },
                        onUse = actions.openCreator, onEdit = actions.openTemplateEditor, onDelete = actions.deleteTemplate,
                    )
                    tab == "square" -> SquareScreen(squareStore, square, state.personalTemplates, requestAccount, publishPicker) { publishPicker = false }
                    tab == "profile" -> ProfileScreen(
                        state.scenario, actions, openAccessibilitySettings, tools, saveModel, clearModel,
                        pageReaderConnected, mikaConnected, squareStore, square, accountReturn != null,
                        onAccountClosed = { accountReturn?.let { tab = it }; accountReturn = null },
                    )
                    detail != null -> CardDetail(detail, state.cards, actions, state.sequence == null, saveMeituanRoute, pickupTemplate)
                    shopping && tools != null -> shoppingState.SaveableStateProvider("shopping") {
                        ShoppingScreen(state.cards, state.sequence, tools, actions, saveShopping,
                            onOpenCard = { detailId = it })
                    }
                    else -> SpaceScreen(
                        state.cards, actions, state.sequence, onOpenCard = { detailId = it },
                        savedShopping = tools?.shopping,
                        pickupTemplate = pickupTemplate,
                        shoppingFavorite = tools?.shoppingFavorite == true,
                        onToggleShoppingFavorite = toggleShoppingFavorite,
                        onAdd = { pickingTemplate = true },
                        onOpenShopping = if (shoppingAvailable) ({
                            shopping = true
                            if (pair.size == 2) actions.refreshSequence(pair)
                        }) else null,
                    )
                }
            }
        }

        if (draft != null) {
            CreatorScreen(
                draft = draft,
                templates = state.templates,
                cards = state.cards,
                modelAvailable = tools?.model != null,
                actions = actions.copy(
                    closeCreator = {
                        if (!draft.saving) returnToSpaceAfterSave = false
                        actions.closeCreator()
                    },
                    saveDraft = {
                        returnToSpaceAfterSave = draft.personalTemplateId == null && draft.ruleChangeCard == null
                        if (editorFromPicker) newTemplateId = draft.personalTemplateId
                        actions.saveDraft()
                    },
                ),
            )
        } else if (pickingTemplate) {
            CardTemplatePicker(
                state.personalTemplates.mapNotNull { it.published }.sortedBy { it.id != newTemplateId },
                square.installed.map { it.definition() },
                onDismiss = { pickingTemplate = false; newTemplateId = null },
                onUse = { pickingTemplate = false; newTemplateId = null; actions.openCreator(it) },
                onCreate = {
                    pickingTemplate = false
                    editorFromPicker = true
                    actions.openTemplateEditor(null)
                },
            )
        }
    }
}

/** 底部固定为四个主入口，选购由空间内的卡片打开。 */
@Composable
private fun DockNavigation(selectedTab: String, onSelect: (String) -> Unit) {
    val labels = listOf("空间", "模板", "广场", "我的")
    val tags = listOf("space", "templates", "square", "profile")
    val icons = listOf(R.drawable.nav_space, R.drawable.nav_templates, R.drawable.nav_square, R.drawable.nav_me)
    Column {
        HorizontalDivider(color = DockColors.Outline)
        NavigationBar(containerColor = DockColors.Background, tonalElevation = 0.dp) {
            labels.forEachIndexed { index, label ->
                NavigationBarItem(
                    selected = selectedTab == tags[index],
                    onClick = { onSelect(tags[index]) },
                    icon = { Icon(painterResource(icons[index]), contentDescription = null) },
                    label = { Text(label) },
                    modifier = Modifier.testTag("tab_${tags[index]}"),
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = Color.Transparent,
                        selectedIconColor = DockColors.Mint,
                        selectedTextColor = DockColors.Mint,
                    ),
                )
            }
        }
    }
}
