package com.life0423.carddock.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.life0423.carddock.core.session.PrototypeActions
import com.life0423.carddock.core.session.PrototypeState
import com.life0423.carddock.ui.theme.CardDockTheme
import com.life0423.carddock.ui.theme.DockColors

private enum class DockTab(val label: String, val tag: String, val icon: () -> ImageVector) {
    Space("空间", "space", { DockIcons.Layers }),
    Assistant("助手", "assistant", { Icons.Rounded.Star }),
    Templates("模板", "templates", { DockIcons.Folder }),
    Profile("我的", "profile", { DockIcons.PersonOutline }),
}

/** 页面只持有导航和表单状态。关闭预览后恢复空间的筛选和滚动位置。 */
@Composable
fun CardDockApp(
    state: PrototypeState,
    actions: PrototypeActions,
    sharedPage: String? = null,
    onShareConsumed: () -> Unit = {},
    openAccessibilitySettings: () -> Unit = {},
) {
    CardDockTheme {
        var tab by rememberSaveable { mutableStateOf(DockTab.Space) }
        var pageDraft by rememberSaveable { mutableStateOf<String?>(null) }
        var cardId by rememberSaveable { mutableStateOf<String?>(null) }
        var groupId by rememberSaveable { mutableStateOf<String?>(null) }
        var orderCreator by rememberSaveable { mutableStateOf(false) }
        var beforeSave by rememberSaveable { mutableStateOf<List<String>?>(null) }
        val card = state.cards.firstOrNull { it.id == cardId }
        val group = state.groups.firstOrNull { it.id == groupId }
        val pageState = rememberSaveableStateHolder()
        val snackbar = remember { SnackbarHostState() }

        LaunchedEffect(sharedPage, state.draft, pageDraft, state.loading) {
            if (sharedPage != null && state.draft == null && pageDraft == null && !state.loading) {
                pageDraft = sharedPage
                onShareConsumed()
            }
        }
        LaunchedEffect(beforeSave, state.draft, state.cards) {
            val previous = beforeSave ?: return@LaunchedEffect
            if (state.draft != null) return@LaunchedEffect
            val saved = state.cards.firstOrNull { it.id !in previous } ?: return@LaunchedEffect
            tab = DockTab.Space
            pageState.removeState(DockTab.Space.tag)
            groupId = null
            cardId = saved.id
            beforeSave = null
            orderCreator = false
        }
        LaunchedEffect(state.notice) {
            state.notice?.let {
                snackbar.showSnackbar(it)
                actions.dismissNotice()
            }
        }

        Scaffold(
            containerColor = DockColors.Background,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (card == null && group == null) NavigationBar(containerColor = DockColors.Surface, tonalElevation = 0.dp) {
                    DockTab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = { Icon(item.icon(), null) },
                            label = { Text(item.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = DockColors.Mint, selectedTextColor = DockColors.Mint,
                                indicatorColor = DockColors.Raised, unselectedIconColor = DockColors.Muted,
                                unselectedTextColor = DockColors.Muted,
                            ),
                            modifier = Modifier.testTag("tab_${item.tag}"),
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when {
                    state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    card != null -> PreviewWindow(card.title, { cardId = null }, closeLabel = "收起") {
                        CardDetail(card, actions)
                    }
                    group != null -> pageState.SaveableStateProvider("group:${group.id}") {
                        GroupPreview(group, state.cards, state.notice, actions, onClose = { groupId = null })
                    }
                    else -> pageState.SaveableStateProvider(tab.tag) {
                        when (tab) {
                            DockTab.Space -> SpaceScreen(state, actions,
                                onCreatePage = { pageDraft = "" },
                                onBrowseTemplates = { tab = DockTab.Templates },
                                onOpenCard = { groupId = null; cardId = it },
                                onOpenGroup = { cardId = null; groupId = it })
                            DockTab.Assistant -> AssistantScreen(
                                state.cards, state.groups, actions.createGroup, actions.dissolveGroup,
                                onOpenSettings = { tab = DockTab.Profile })
                            DockTab.Templates -> TemplatesScreen(state.cards, state.templates, actions.openCreator) {
                                orderCreator = true
                                actions.openCreator(null)
                            }
                            DockTab.Profile -> ProfileScreen(state.scenario, actions, openAccessibilitySettings)
                        }
                    }
                }
            }
        }

        state.draft?.let { draft ->
            CreatorSheet(draft, state.templates, actions.copy(
                saveDraft = {
                    beforeSave = state.cards.map { it.id }
                    actions.saveDraft()
                },
                closeCreator = {
                    beforeSave = null
                    orderCreator = false
                    actions.closeCreator()
                },
            ), initialMeituan = orderCreator)
        }
        pageDraft?.let { initial ->
            PageCreator(initial, save = { title, target ->
                beforeSave = state.cards.map { it.id }
                actions.savePage(title, target).also { if (!it) beforeSave = null }
            }, onClose = { pageDraft = null })
        }
    }
}
