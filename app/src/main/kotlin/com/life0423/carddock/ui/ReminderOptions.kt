package com.life0423.carddock.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.life0423.carddock.CardDockApplication
import com.life0423.carddock.core.rule.LocalReminder
import com.life0423.carddock.core.rule.parseLocalReminder
import java.time.*
import java.time.format.DateTimeFormatter

/** 试运行只预览；空间卡允许改时和暂停，并展示系统实际登记状态。 */
@Composable internal fun ReminderOptions(plan: LocalReminder, cardId: String? = null, compact: Boolean = false) {
    val context = LocalContext.current
    val app = (context.applicationContext as CardDockApplication).container
    val alarm = app.reminders
    var tick by remember { mutableIntStateOf(0) }
    fun refresh() {
        val s = app.controller.state.value
        if (!s.loading && s.cardsReadable) alarm.sync(s.cards.mapNotNull { c -> c.rule.localReminder?.let { c.id to it } }.toMap())
        tick++
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner,plan,cardId) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh() }
        owner.lifecycle.addObserver(observer);onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val grant = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    val changes by alarm.changes.collectAsState()
    val stamp = remember(plan,tick,cardId,changes) { cardId?.let(alarm::scheduled) }
    val fired = remember(plan,tick,cardId,changes) { cardId?.let(alarm::lastFired) }
    val fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    val time = stamp?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(fmt) }
    Text(when {
        !plan.enabled -> "已暂停"
        !alarm.notifications() -> "未启用 · 需要通知权限"
        !alarm.precise() -> "未启用 · 需要准时提醒权限"
        cardId == null -> "加入空间后启用 · ${plan.label}"
        time != null -> "下次提醒 $time"
        fired != null -> "本次提醒已发出"
        else -> "尚无下一次提醒"
    })
    if (compact) return
    var requested by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if (!alarm.notifications()) TextButton({
        if (!requested && Build.VERSION.SDK_INT >= 33 && !context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled())
            { requested = true; grant.launch(Manifest.permission.POST_NOTIFICATIONS) }
        else if (!context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()) context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))
        else context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID,com.life0423.carddock.platform.ALARM_CHANNEL))
    }) { Text("开启通知") }
    if (!alarm.precise() && Build.VERSION.SDK_INT >= 31) TextButton({
        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:${context.packageName}")))
    }) { Text("允许准时提醒") }
    if (cardId != null) {
        var editing by remember { mutableStateOf(false) }
        TextButton({ app.controller.actions.editReminder(cardId,plan.copy(enabled=!plan.enabled)) }) { Text(if (plan.enabled) "暂停提醒" else "启用提醒") }
        TextButton({ editing = true }) { Text("修改提醒") }
        if (editing) {
            var daily by remember { mutableStateOf(plan.date == null) }
            var day by remember { mutableStateOf(plan.date ?: LocalDate.now().toString()) }
            var clock by remember { mutableStateOf(plan.clock) }
            var subject by remember { mutableStateOf(plan.subject) }
            val updated = parseLocalReminder("${if (daily) "每天" else day} $clock 提醒我$subject",ZonedDateTime.now())
            AlertDialog(onDismissRequest={editing=false},title={Text("修改提醒")},text={
                androidx.compose.foundation.layout.Column {
                    androidx.compose.foundation.layout.Row {
                        FilterChip(selected=!daily,onClick={daily=false},label={Text("仅一次")})
                        FilterChip(selected=daily,onClick={daily=true},label={Text("每天")})
                    }
                    if (!daily) OutlinedTextField(day,{day=it},singleLine=true,label={Text("日期（年-月-日）")})
                    OutlinedTextField(clock,{clock=it},singleLine=true,label={Text("时间（时:分）")})
                    OutlinedTextField(subject,{subject=it},label={Text("提醒事项")},singleLine=true)
                }
            },dismissButton={TextButton({editing=false}){Text("取消")}},confirmButton={
                Button({updated?.let { app.controller.actions.editReminder(cardId,it.copy(enabled=plan.enabled)) };editing=false},
                    enabled=updated?.next(ZonedDateTime.now())!=null){Text("保存修改")}
            })
        }
    }
}
