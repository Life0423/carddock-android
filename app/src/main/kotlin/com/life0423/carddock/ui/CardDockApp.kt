package com.life0423.carddock.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Star
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
        var shopping by rememberSaveable { mutableStateOf(false) }
        var pickingTemplate by rememberSaveable { mutableStateOf(false) }
        var editorFromPicker by rememberSaveable { mutableStateOf(false) }
        var newTemplateId by rememberSaveable { mutableStateOf<String?>(null) }
        var returnToSpaceAfterSave by rememberSaveable { mutableStateOf(false) }
        var detailId by rememberSaveable { mutableStateOf<String?>(null) }
        val shoppingState = rememberSaveableStateHolder()
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
                    } else if (tab == "space") {
                        Image(painterResource(R.drawable.carddock_logo), null, Modifier.size(32.dp))
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
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (tab == "space" && detail == null && !shopping) {
                        FilledIconButton(
                            onClick = { pickingTemplate = true },
                            enabled = !state.loading && state.sequence == null,
                            modifier = Modifier.testTag("open_creator"),
                        ) { Icon(Icons.Rounded.Add, "添加功能卡") }
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
                    tab == "square" -> SquareScreen(squareStore, square, state.personalTemplates, requestAccount)
                    tab == "profile" -> ProfileScreen(
                        state.scenario, actions, openAccessibilitySettings, tools, saveModel, clearModel,
                        pageReaderConnected, mikaConnected, squareStore, square, accountReturn != null,
                        onAccountClosed = { accountReturn?.let { tab = it }; accountReturn = null },
                    )
                    detail != null -> CardDetail(detail, state.cards, actions, state.sequence == null, saveMeituanRoute)
                    shopping && tools != null -> shoppingState.SaveableStateProvider("shopping") {
                        ShoppingScreen(state.cards, state.sequence, tools, actions, saveShopping,
                            onOpenCard = { detailId = it })
                    }
                    else -> SpaceScreen(
                        state.cards, actions, state.sequence, onOpenCard = { detailId = it },
                        shoppingSaved = tools?.shopping != null,
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
    val icons = listOf(DockIcons.Dashboard, DockIcons.GridView, Icons.Rounded.Star, DockIcons.PersonOutline)
    Column {
        HorizontalDivider(color = DockColors.Outline)
        NavigationBar(containerColor = DockColors.Surface, tonalElevation = 0.dp) {
            labels.forEachIndexed { index, label ->
                NavigationBarItem(
                    selected = selectedTab == tags[index],
                    onClick = { onSelect(tags[index]) },
                    icon = { Icon(icons[index], contentDescription = null) },
                    label = { Text(label) },
                    modifier = Modifier.testTag("tab_${tags[index]}"),
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = DockColors.Mint,
                        selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                        selectedTextColor = DockColors.Mint,
                    ),
                )
            }
        }
    }
}
