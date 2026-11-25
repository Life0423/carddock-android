package com.life0423.carddock

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.life0423.carddock.ui.CardDockApp
import com.life0423.carddock.ui.pageLink

/**
 * 把应用级控制器接进 Compose。控制器在 Application 里，旋转重建 Activity 不会新开一套会话。
 * 白色内容背景使用深色系统栏图标。
 */
class MainActivity : ComponentActivity() {
    private val sharedPage = mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readSharedLink(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) readSharedLink(intent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        val controller = (application as CardDockApplication).container.controller
        setContent {
            val viewModel: CardDockViewModel = viewModel(factory = CardDockViewModel.factory(controller))
            val state = viewModel.state.collectAsStateWithLifecycle()
            CardDockApp(state = state.value, actions = viewModel.actions, sharedPage = sharedPage.value,
                onShareConsumed = { sharedPage.value = null }, openAccessibilitySettings = {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
        }
    }

    private fun readSharedLink(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT) ?: return
        sharedPage.value = pageLink(text.toString())?.toASCIIString()
    }
}
