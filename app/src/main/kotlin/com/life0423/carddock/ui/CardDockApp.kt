package com.life0423.carddock.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.life0423.carddock.R
import com.life0423.carddock.core.session.PrototypeActions
import com.life0423.carddock.core.session.PrototypeState
import com.life0423.carddock.ui.theme.CardDockTheme
import com.life0423.carddock.ui.theme.DockColors

/**
 * UI owns only presentation state. All domain mutations are explicit callbacks.
 * 选中页用 rememberSaveable，旋转后还在原页。通知等 Snackbar 退出再清成 null，同一句才能再次投递。
 * 保存一点击就切到空间页，不等写入成功；失败时弹层仍在，底下已经是空间。
 */
@Composable
fun CardDockApp(state: PrototypeState, actions: PrototypeActions) {
    CardDockTheme {
        var selectedTab by rememberSaveable { mutableIntStateOf(0) }
        val snackbarHost = remember { SnackbarHostState() }
        LaunchedEffect(state.notice) {
            state.notice?.let { message ->
                // 等到这条 Snackbar 退出再把 notice 清成 null。留着同一句时，后面的相同文案不会再次投递。
                snackbarHost.showSnackbar(message)
                actions.dismissNotice()
            }
        }

        Scaffold(
            containerColor = DockColors.Background,
            topBar = { BrandHeader() },
            bottomBar = {
                DockNavigation(selectedTab = selectedTab, onSelect = { selectedTab = it })
            },
            snackbarHost = { SnackbarHost(snackbarHost) },
        ) { contentPadding ->
            Box(Modifier.fillMaxSize().padding(contentPadding)) {
                if (state.loading) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else {
                    when (selectedTab) {
                        0 -> SpaceScreen(state.cards, actions)
                        1 -> TemplatesScreen(state.templates, actions.openCreator)
                        else -> ProfileScreen(state.scenario, actions)
                    }
                }
            }
        }

        state.draft?.let { draft ->
            CreatorSheet(
                draft = draft,
                templates = state.templates,
                actions = actions.copy(saveDraft = {
                    actions.saveDraft()
                    // 先切页再等结果。写入失败时草稿还在，弹层继续盖住已经切过来的空间页。
                    selectedTab = 0
                }),
            )
        }
    }
}

@Composable
private fun BrandHeader() {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 22.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Image(painterResource(R.drawable.carddock_logo), null, Modifier.size(38.dp))
        Text(
            "卡坞",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        TextPill("演示模式", DockColors.Amber)
    }
}

@Composable
private fun DockNavigation(selectedTab: Int, onSelect: (Int) -> Unit) {
    val labels = listOf("空间", "模板", "我的")
    val tags = listOf("space", "templates", "profile")
    val icons = listOf(DockIcons.Dashboard, DockIcons.GridView, DockIcons.PersonOutline)
    NavigationBar(containerColor = DockColors.Background, tonalElevation = 0.dp) {
        labels.forEachIndexed { index, label ->
            NavigationBarItem(
                selected = selectedTab == index,
                onClick = { onSelect(index) },
                icon = { Icon(icons[index], contentDescription = null) },
                label = { Text(label) },
                modifier = Modifier.testTag("tab_${tags[index]}"),
            )
        }
    }
}
