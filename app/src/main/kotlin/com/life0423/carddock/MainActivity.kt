package com.life0423.carddock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import com.life0423.carddock.core.model.RunPhase
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.life0423.carddock.platform.CardDockAccessibilityService
import com.life0423.carddock.platform.mika.DockMikaHandoff
import com.life0423.carddock.ui.CardDockApp

/**
 * 把应用级控制器接进 Compose。控制器在 Application 里，旋转重建 Activity 不会新开一套会话。
 * 背景固定是暗色，系统栏用 SystemBarStyle.dark，图标才是浅色。
 */
class MainActivity : ComponentActivity() {
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        (application as CardDockApplication).container.dockWindowFocused = hasFocus
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val container = (application as CardDockApplication).container
        val controller = container.controller
        setContent {
            val viewModel: CardDockViewModel = viewModel(factory = CardDockViewModel.factory(controller))
            val state = viewModel.state.collectAsStateWithLifecycle()
            val tools = container.tools.collectAsStateWithLifecycle()
            val readerConnected = CardDockAccessibilityService.connected.collectAsStateWithLifecycle()
            val assistant = DockMikaHandoff.display.collectAsStateWithLifecycle()
            // 长时间逐单读取需要卡坞保持可见。查询结束或页面销毁后恢复系统息屏策略。
            val view = LocalView.current
            val running = state.value.draft?.phase == RunPhase.RUNNING ||
                state.value.cards.any { it.run?.phase == RunPhase.RUNNING }
            DisposableEffect(view, running) {
                if (running) view.keepScreenOn = true
                onDispose { if (running) view.keepScreenOn = false }
            }
            CardDockApp(
                state = state.value, actions = viewModel.actions, openAccessibilitySettings = {
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                tools = tools.value,
                squareStore = container.square,
                saveModel = container::saveModel,
                clearModel = container::clearModel,
                saveShopping = container::saveShopping,
                toggleShoppingFavorite = container::toggleShoppingFavorite,
                saveMeituanRoute = container::saveMeituanRoute,
                pageReaderConnected = readerConnected.value,
                mikaConnected = assistant.value != null,
            )
        }
    }
}
