package com.life0423.carddock.ui

import android.Manifest
import android.app.job.JobScheduler
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.model.TemplateDefinition
import com.life0423.carddock.displayRankTemplate
import com.life0423.carddock.platform.*
import com.life0423.carddock.ui.theme.DockColors
import com.life0423.carddock.watchTemplate
import java.io.IOException

/** 系统通知由用户主动开启；关闭通知时照常保存价格记录。 */
@Composable
internal fun PriceWatchControls(history: PriceHistory? = null) {
    val context = LocalContext.current
    val manager = context.getSystemService(NotificationManager::class.java)
    fun enabled() = manager.areNotificationsEnabled() && manager.getNotificationChannel(WATCH_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    val scheduler = context.getSystemService(JobScheduler::class.java)
    // 只有该订货号已经有跟踪历史才显示登记状态；试运行预览沿用授权入口。
    var scheduled by remember(history?.checkedAt) {
        mutableStateOf(history != null && scheduler.getPendingJob(WATCH_JOB) != null)
    }
    var granted by remember { mutableStateOf(enabled()) }
    var requested by rememberSaveable { mutableStateOf(false) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner, history?.checkedAt) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                granted = enabled()
                scheduled = history != null && scheduler.getPendingJob(WATCH_JOB) != null
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = enabled() }
    if (history != null) {
        val schedule = if (scheduled) "已登记每日联网核价" else "每日核价未登记"
        Text(schedule + if (granted) " · 降价通知已开启" else " · 降价通知未开启",
            style = MaterialTheme.typography.bodySmall, color = DockColors.Muted)
        if (!scheduled) TextButton(onClick = {
            val container = (context.applicationContext as com.life0423.carddock.CardDockApplication).container
            val state = container.controller.state.value
            if (!state.loading && state.cardsReadable) container.marketWatch.sync(state.cards.filter { it.isMarketWatch() })
            scheduled = scheduler.getPendingJob(WATCH_JOB) != null
        }) { Text("重试登记") }
    }
    if (!granted) TextButton(onClick = {
        if (Build.VERSION.SDK_INT >= 33 && !requested && !manager.areNotificationsEnabled()) {
            requested = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            val settings = if (manager.areNotificationsEnabled() && manager.getNotificationChannel(WATCH_CHANNEL) != null)
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_CHANNEL_ID, WATCH_CHANNEL)
            else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            context.startActivity(settings.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
    }) { Text("开启降价通知") }
}

/** 离线详情与官网弹窗使用同一价格时间线，同价的重复采集不产生变价记录。 */
internal fun androidx.compose.foundation.lazy.LazyListScope.priceHistory(series: PriceHistory) {
    item {
        Text(if (series.backgroundChecks.isEmpty()) "后台核价尚无成功记录"
            else "最近后台核价 " + series.backgroundChecks.takeLast(3).asReversed().joinToString("、") { formatTimestamp(it) },
            color = DockColors.Muted, style = MaterialTheme.typography.bodySmall)
    }
    item { Text("最近核价 " + formatTimestamp(series.checkedAt), color = DockColors.Muted, style = MaterialTheme.typography.bodySmall) }
    items(series.changes.size) { offset ->
        val index = series.changes.lastIndex - offset
        val point = series.changes[index]
        val change = series.changes.getOrNull(index - 1)?.let { point.yuan - it.yuan }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("¥${point.yuan}", style = MaterialTheme.typography.titleMedium)
                Text(formatTimestamp(point.at), style = MaterialTheme.typography.bodySmall, color = DockColors.Muted)
            }
            Text(when {
                change == null && series.changes.size < 60 -> "首次记录"
                change == null -> "历史标价"
                change < 0 -> "降 ¥${-change}"
                else -> "涨 ¥$change"
            }, color = if (change != null && change < 0) DockColors.Sage else DockColors.Muted)
        }
    }
}

/** 两个系列按同一零售价排序。进入配置后可查官方购买页、历史或以该配置创建跟踪卡。 */
@Composable
internal fun DisplayMarketScreen(cards: List<CardRecord>, history: Map<String, PriceHistory>,
    onTemplate: (TemplateDefinition) -> Unit, onWatch: (TemplateDefinition) -> Unit) {
    var catalog by remember { mutableStateOf<DisplayCatalog?>(null) }
    var error by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val openUri = LocalUriHandler.current
    LaunchedEffect(attempt) {
        busy = true
        error = false
        try { catalog = loadDisplayCatalog() } catch (_: IOException) { error = true } finally { busy = false }
    }
    val watched = cards.filter { it.isMarketWatch() }
    LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item {
            Surface(color = DockColors.Text, contentColor = DockColors.Ticket, shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Apple 中国大陆官网", style = MaterialTheme.typography.labelLarge)
                    Text("显示器配置价格榜", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    catalog?.let { Text("${it.offers.size} 个配置  ·  人民币零售价升序", style = MaterialTheme.typography.bodyMedium) }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else catalog?.let { Text("采集于 " + formatTimestamp(it.at), style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton({ onTemplate(displayRankTemplate) }, enabled = catalog != null) { Text("保存为榜单模板") }
                Spacer(Modifier.weight(1f))
                TextButton({ attempt++ }, enabled = !busy) { Text("刷新价格") }
            }
            if (error) Text("这次未能取得完整官网价格，可稍后刷新。", color = DockColors.Muted)
            if (watched.isNotEmpty()) PriceWatchControls()
        }
        itemsIndexed(catalog?.offers.orEmpty(), key = { _, offer -> offer.part }) { index, offer ->
            Surface(onClick = { selected = offer.part }, color = DockColors.Surface,
                border = BorderStroke(1.dp, DockColors.Outline), shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text((index + 1).toString(), Modifier.width(28.dp), color = DockColors.Muted,
                        style = MaterialTheme.typography.labelLarge)
                    Column(Modifier.weight(1f)) {
                        Text(offer.model, fontWeight = FontWeight.SemiBold)
                        Text(offer.configuration, color = DockColors.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("¥${offer.yuan}", fontWeight = FontWeight.Bold)
                        if (watched.any { it.rule.targetKey == offer.part }) Text("跟踪中", color = DockColors.Mint,
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
    val offer = catalog?.offers?.firstOrNull { it.part == selected } ?: return
    val series = history[offer.part]
    val tracking = watched.any { it.rule.targetKey == offer.part }
    AlertDialog(onDismissRequest = { selected = null }, title = { Text(offer.model) }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text(offer.configuration); Text(offer.part, color = DockColors.Muted) }
            item { Text("¥${offer.yuan}", style = MaterialTheme.typography.headlineMedium); Text(PRICE_BASIS, style = MaterialTheme.typography.bodySmall) }
            item { TextButton({ openUri.openUri(offer.url) }) { Text("查看官网配置") } }
            item { Text(if (tracking) "价格记录" else "每天联网核价，标价下降时提醒。") }
            if (series != null) priceHistory(series)
            if (tracking) item { Text("移除空间中的跟踪卡即可停止。", style = MaterialTheme.typography.bodySmall); PriceWatchControls(series) }
        }
    }, confirmButton = {
        if (tracking) TextButton({ selected = null }) { Text("完成") }
        else Button({ selected = null; onWatch(watchTemplate(offer)) }) { Text("创建跟踪卡") }
    }, dismissButton = { if (!tracking) TextButton({ selected = null; onTemplate(watchTemplate(offer)) }) { Text("自建跟踪模板") } })
}

