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
import java.io.FileDescriptor
import java.io.PrintWriter

/**
 * 把应用级控制器接进 Compose。控制器在 Application 里，旋转重建 Activity 不会新开一套会话。
 * 纸色界面使用深色系统栏图标。
 */
class MainActivity : ComponentActivity() {
    /** 真机验收可从系统 dump 读取阶段，无需在试运行中启动会暂停无障碍服务的界面取树工具。 */
    override fun dump(prefix: String, fd: FileDescriptor?, writer: PrintWriter, args: Array<out String>?) {
        // 仅输出路线和时间，独立核验不读取卡名、订单或前台用户的界面内容。
        if (args?.singleOrNull() == "carddock-meal") {
            writer.println((application as CardDockApplication).container.nativeDevice.lastMealStartup)
            return
        }
        if (args?.singleOrNull() == "carddock-background") {
            val state = (application as CardDockApplication).container.controller.state.value
            for (card in state.cards.filter { it.run != null }) {
                writer.println("CardDockBackground source=${card.rule.sourceId} phase=${card.run?.phase} start=${card.run?.startedAt} end=${card.run?.endedAt} fetched=${card.snapshot?.fetchedAt}")
            }
            return
        }
        if (args?.singleOrNull() != "carddock-trial") {
            super.dump(prefix, fd, writer, args)
            return
        }
        val draft = (application as CardDockApplication).container.controller.state.value.draft
        writer.println("${prefix}CardDockTrial phase=${draft?.phase?.name ?: "CLOSED"} preview=${draft?.preview != null} reader=${CardDockAccessibilityService.connected.value}")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        (application as CardDockApplication).container.dockWindowFocused = hasFocus
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.DKGRAY),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.DKGRAY),
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
                marketHistory = container.marketWatch.state.collectAsStateWithLifecycle().value,
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
