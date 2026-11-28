package com.life0423.carddock.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.ui.ModelSetupScreen
import com.wangzan101.mika.ui.theme.MikaTheme

/** 在同一 APK 内直接打开 Mika 的看图用途选择器，复用已连接的服务商和鉴权。 */
class VisionSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = ProviderStore.get(this)
        setContent {
            MikaTheme {
                ModelSetupScreen(store, onboarding = false, onBack = ::finish, onDone = ::finish, initialRole = ModelRole.Vision)
            }
        }
    }
}
