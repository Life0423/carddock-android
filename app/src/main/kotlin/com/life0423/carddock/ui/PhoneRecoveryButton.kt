package com.life0423.carddock.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.life0423.carddock.core.model.PhoneCapability
import com.life0423.carddock.phone.PhoneSetupActivity
import com.life0423.carddock.platform.CardDockAccessibilityService
import com.life0423.carddock.platform.mika.DockMikaHandoff

/** 只在查询确实缺少能力时给出入口。回到卡片后按实时连接状态收起，保留原来的重试动作。 */
@Composable
internal fun PhoneRecoveryButton(capability: PhoneCapability?) {
    val context = LocalContext.current
    when (capability) {
        PhoneCapability.PAGE_READER -> {
            val connected by CardDockAccessibilityService.connected.collectAsStateWithLifecycle()
            if (!connected) OutlinedButton({ context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                Text("开启页面读取")
            }
        }
        PhoneCapability.HELPER -> {
            val binder by DockMikaHandoff.display.collectAsStateWithLifecycle()
            if (binder == null) OutlinedButton({ context.startActivity(Intent(context, PhoneSetupActivity::class.java)) }) {
                Text("连接后台助手")
            }
        }
        null -> Unit
    }
}
