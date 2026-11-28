package com.life0423.carddock.phone

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.life0423.carddock.ui.theme.CardDockTheme
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.setup.DeveloperSettings
import com.wangzan101.mika.cap.phone.setup.PairingNotifier
import com.wangzan101.mika.settings.SystemStatusMonitor
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.ui.Fix
import com.wangzan101.mika.ui.PhoneSetupScreen
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.launch

/** 显示任务真正使用的内嵌助手。旧卡的页面核验助手保留独立入口和配对。 */
@Composable
internal fun MikaPhoneSettings(close: () -> Unit) {
    val context = LocalContext.current
    val phone = remember { PhoneServices.of(context) }
    val monitor = remember { SystemStatusMonitor(context, phone.shellControl) }
    val status by monitor.status.collectAsState()
    val scope = rememberCoroutineScope()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { monitor.refresh() }
    LifecycleResumeEffect(monitor) {
        monitor.start()
        monitor.refresh()
        phone.shellManager.engine.need()
        onPauseOrDispose { monitor.stop() }
    }
    CardDockTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
                TextButton(close) { Text("返回卡坞") }
                Text("手机助手", Modifier.padding(horizontal = 18.dp), style = MaterialTheme.typography.headlineSmall)
                MikaTheme {
                    PhoneSetupScreen(status, repair = false, onFix = { fix ->
                        val intents = when (fix) {
                            Fix.OpenAboutPhone -> DeveloperSettings.aboutPhone()
                            Fix.OpenDeveloperOptions -> DeveloperSettings.developerOptionsPage()
                            Fix.OpenUsbDebugging -> DeveloperSettings.usbDebuggingPage()
                            Fix.OpenWirelessDebugging -> DeveloperSettings.wirelessDebuggingPage()
                            Fix.OpenWifi -> DeveloperSettings.wifiPage()
                            Fix.OpenAccessibility -> listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            Fix.OpenAppInfo -> listOf(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)))
                            Fix.AllowNotifications -> {
                                if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                emptyList()
                            }
                            Fix.RetryPhoneHelper -> {
                                scope.launch { phone.shellControl.retry() }
                                emptyList()
                            }
                            Fix.OpenPairingChannel, Fix.OpenAttentionChannel, Fix.OpenResultChannel, Fix.OpenProgressChannel -> {
                                val channel = when (fix) {
                                    Fix.OpenPairingChannel -> PairingNotifier.ensureChannel(context).id
                                    Fix.OpenAttentionChannel -> TaskService.CHANNEL_ATTENTION
                                    Fix.OpenProgressChannel -> TaskService.CHANNEL_PROGRESS
                                    else -> TaskService.CHANNEL_RESULT
                                }
                                listOf(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    .putExtra(Settings.EXTRA_CHANNEL_ID, channel),
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                            }
                            else -> emptyList()
                        }
                        intents.firstOrNull { it.resolveActivity(context.packageManager) != null }?.let { context.startActivity(it) }
                    }, onClose = close)
                }
            }
        }
    }
}
