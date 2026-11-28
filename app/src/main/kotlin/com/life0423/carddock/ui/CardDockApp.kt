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
    marketHistory: Map<String, com.life0423.carddock.platform.PriceHistory> = emptyMap(),
    saveModel: (String, String, String) -> Unit = { _, _, _ -> },
    clearModel: () -> Unit = {},
    saveShopping: (String) -> Unit = {},
    pageReaderConnected: Boolean = false,
    mikaConnected: Boolean = false,
    routeSpaceRequest: Int = 0, routeDrafts: Boolean = false,
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
        var returnToSpaceAfterSave by rememberSaveable { mutableStateOf(false) }
        var detailId by rememberSaveable { mutableStateOf<String?>(null) }
        val shoppingState = rememberSaveableStateHolder()
        // 旧取件卡的附加快捷方式指向一份具体的、已发布的本机模板。
        val pickupTemplate = state.personalTemplates.mapNotNull { it.published }.filter {
            it.pickupEntry != null && it.rule.sourceId == com.life0423.carddock.platform.CAINIAO_PACKAGE
        }.singleOrNull()
        val detail = state.cards.firstOrNull { it.id == detailId }
        val pair = JdProduct.entries.mapNotNull { shoppingCard(state.cards, it)?.id }
        var marketTab by rememberSaveable { mutableIntStateOf(0) }
        val shoppingAvailable = tools != null
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
        val context = androidx.compose.ui.platform.LocalContext.current
        var showDrafts by rememberSaveable { mutableStateOf(false) }
        // 草稿和实例各回所属分页。
        LaunchedEffect(routeSpaceRequest) {
            if (routeSpaceRequest > 0) {
                tab = if (routeDrafts) "templates" else "space"
                showDrafts = routeDrafts
                shopping = false
                detailId = null
                pickingTemplate = false
            }
        }
        fun newRoute() = context.openRoutes()
        BackHandler(state.draft == null && tab == "space" && (detail != null || shopping)) {
            if (detail != null) detailId = null else shopping = false
        }
        val draft = state.draft
        if (draft == null) Scaffold(
            containerColor = DockColors.Background,
            topBar = {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 70.dp)
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
                            "templates" -> "模板库"
                            "square" -> "模板广场"
                            "profile" -> "我的"
                            else -> when {
                                detail != null -> "卡片详情"
                                shopping -> "显示器选购"
                                else -> "卡片空间"
                            }
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineSmall, fontSize = 30.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (tab == "space" && detail == null && !shopping) {
                        val ids = state.cards.filterNot { it.rule.sourceId.startsWith("demo.") }.map { it.id }
                        if ((ids.isNotEmpty() || shoppingAvailable) && state.sequence == null) Box {
                            IconButton(onClick = { spaceMenu = true }) { Icon(DockIcons.MoreHoriz, "空间操作") }
                            DropdownMenu(spaceMenu, { spaceMenu = false }) {
                                if (shoppingAvailable) DropdownMenuItem(text = { Text("显示器选购") }, onClick = {
                                    spaceMenu = false
                                    marketTab = 0
                                    shopping = true
                                })
                                if (ids.isNotEmpty()) DropdownMenuItem(text = { Text("顺序核验") }, onClick = {
                                    spaceMenu = false
                                    actions.refreshSequence(ids)
                                })
                            }
                        }
                        FilledIconButton(
                            onClick = { pickingTemplate = true },
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = DockColors.Text, contentColor = DockColors.Surface),
                            enabled = !state.loading && state.sequence == null,
                            modifier = Modifier.testTag("open_creator"),
                        ) { Icon(Icons.Rounded.Add, "添加功能卡") }
                    } else if (tab == "templates") {
                        FilledTonalButton({ newRoute() }, Modifier.testTag("new_template"),
                            enabled = state.sequence == null && !state.loading,
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = DockColors.Mint,
                                contentColor = MaterialTheme.colorScheme.onPrimary)) {
                            Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                            Text("新建")
                        }
                    } else if (tab == "square" && square.available) {
                        OutlinedButton({ publishPicker = true; if (!square.signedIn) requestAccount() },
                            enabled = !square.busy) { Text("发布") }
                    } else if (tab == "profile") {
                        androidx.compose.foundation.Image(painterResource(R.drawable.ticket_wallet), null, Modifier.size(38.dp))
                    }
                }
            },
            bottomBar = {
                if (tab != "space" || detail == null) DockNavigation(tab) {
                    if (it != "profile") accountReturn = null
                    if (it != "square") publishPicker = false
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
                        showDrafts = showDrafts, onSection = { showDrafts = it },
                        onUse = actions.openCreator, onEdit = actions.openTemplateEditor, onDelete = actions.deleteTemplate, onDiscard = actions.discardTemplateDraft,
                    )
                    tab == "square" -> SquareScreen(squareStore, square, state.personalTemplates, requestAccount, publishPicker) { publishPicker = false }
                    tab == "profile" -> ProfileScreen(
                        state.scenario, actions, openAccessibilitySettings, tools, saveModel, clearModel,
                        pageReaderConnected, mikaConnected, squareStore, square, accountReturn != null,
                        onAccountClosed = { if (!square.signedIn) publishPicker = false; accountReturn?.let { tab = it }; accountReturn = null },
                    )
                    detail != null -> CardDetail(detail, state.cards, actions, state.sequence == null, saveMeituanRoute, pickupTemplate,
                        marketHistory[detail.rule.targetKey].takeIf { detail.rule.sourceId == com.life0423.carddock.platform.APPLE_STORE })
                    shopping && tools != null -> shoppingState.SaveableStateProvider("shopping") {
                        Column {
                            TabRow(selectedTabIndex = marketTab) {
                                listOf("官网价格榜", "京东 A/B").forEachIndexed { i, label ->
                                    Tab(selected = marketTab == i, onClick = { marketTab = i }, text = { Text(label) })
                                }
                            }
                            if (marketTab == 0) DisplayMarketScreen(state.cards, marketHistory,
                                onTemplate = { actions.openTemplateEditor(null); actions.chooseDraftTemplate(it) },
                                onWatch = actions.openCreator)
                            else ShoppingScreen(state.cards, state.sequence, tools, actions, saveShopping,
                                onOpenCard = { detailId = it })
                        }
                    }
                    else -> SpaceScreen(
                        state.cards, actions, state.sequence, onOpenCard = { detailId = it },
                        savedShopping = tools?.shopping,
                        pickupTemplate = pickupTemplate,
                        shoppingFavorite = tools?.shoppingFavorite == true,
                        onToggleShoppingFavorite = toggleShoppingFavorite,
                        onAdd = { pickingTemplate = true },
                        onOpenRank = { marketTab = 0; shopping = true },
                        onOpenShopping = if (shoppingAvailable && (pair.isNotEmpty() || tools?.shopping != null)) ({
                            shopping = true
                            marketTab = if (pair.size == 2 || tools?.shopping != null) 1 else 0
                            if (pair.size == 2) actions.refreshSequence(pair)
                        }) else null,
                    )
                }
            }
        }

        if (draft != null) {
            CreatorScreen(
                draft = draft,
                listCode = pickupTemplate?.pickupEntry,
                templates = state.templates,
                cards = state.cards,
                chooseAnother = {
                    returnToSpaceAfterSave = false
                    pickingTemplate = true
                    actions.closeCreator()
                },
                modelAvailable = tools?.model != null,
                actions = actions.copy(
                    closeCreator = {
                        if (!draft.saving) returnToSpaceAfterSave = false
                        actions.closeCreator()
                    },
                    saveDraft = {
                        returnToSpaceAfterSave = draft.personalTemplateId == null && draft.ruleChangeCard == null
                        actions.saveDraft()
                    },
                ),
            )
        } else if (pickingTemplate) {
            CardTemplatePicker(
                state.personalTemplates.mapNotNull { it.published },
                square.installed.map { it.definition() },
                onDismiss = { pickingTemplate = false },
                onUse = { pickingTemplate = false; actions.openCreator(it) },
                onCreate = {
                    pickingTemplate = false
                    newRoute()
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
    val active = listOf(R.drawable.ticket_space_active, R.drawable.ticket_templates_active, R.drawable.ticket_square_active, R.drawable.ticket_me_active)
    val idle = listOf(R.drawable.ticket_space_idle, R.drawable.ticket_templates_idle, R.drawable.ticket_square_idle, R.drawable.ticket_me_idle)
    Column {
        HorizontalDivider(color = DockColors.Outline)
        NavigationBar(containerColor = DockColors.Surface, tonalElevation = 0.dp) {
            labels.forEachIndexed { index, label ->
                NavigationBarItem(
                    selected = selectedTab == tags[index],
                    onClick = { onSelect(tags[index]) },
                    icon = { androidx.compose.foundation.Image(painterResource(if (selectedTab == tags[index]) active[index] else idle[index]), null, Modifier.size(24.dp)) },
                    label = { Text(label) },
                    modifier = Modifier.testTag("tab_${tags[index]}"),
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedIconColor = DockColors.Mint,
                        selectedTextColor = DockColors.Mint,
                    ),
                )
            }
        }
    }
}
