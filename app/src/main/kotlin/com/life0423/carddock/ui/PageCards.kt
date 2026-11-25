package com.life0423.carddock.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.model.PageTarget
import com.life0423.carddock.ui.theme.DockColors
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 同一入口解析粘贴或分享来的 HTTPS 链接。仅打开链接，由用户核对抵达的页面。 */
internal fun pageLink(text: String): URI? {
    val candidate = Regex("""https://[^\s<>"「」【】]+""").find(text)?.value?.trimEnd('。', '，', '）') ?: return null
    val link = try { URI(candidate) } catch (_: java.net.URISyntaxException) { return null }
    return link.takeIf { it.host != null && it.userInfo == null }
}

private fun openPage(context: Context, url: String): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

internal fun Context.livePreviewAvailable(): Boolean =
    Build.VERSION.SDK_INT >= 34 && applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

/** 仅 Debug Manifest 注册预览入口。页面卡可把原始链接交给调试实验。 */
internal fun Context.livePreviewIntent(url: String? = null) =
    Intent().setComponent(ComponentName(packageName, "com.life0423.carddock.platform.mika.LivePreviewActivity"))
        .putExtra("target_url", url)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PageCreator(initialUrl: String, save: suspend (String, PageTarget) -> Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var title by rememberSaveable { mutableStateOf("") }
    var visited by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    val link = remember(url) { pageLink(url)?.toASCIIString() }
    ModalBottomSheet(onDismissRequest = { if (!saving) onClose() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = DockColors.Surface) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("保存页面", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(title, { title = it.take(40) }, Modifier.fillMaxWidth().testTag("page_title"),
                enabled = !saving, label = { Text("卡片名称") }, singleLine = true)
            OutlinedTextField(url, {
                url = it
                visited = null
                confirmed = false
                message = null
            }, Modifier.fillMaxWidth().testTag("page_link"), enabled = !saving,
                label = { Text("来源分享链接") }, placeholder = { Text("https://…") })
            OutlinedButton({
                val target = link ?: return@OutlinedButton
                val opened = openPage(context, target)
                if (opened) visited = target
                confirmed = false
                message = if (opened) null else "设备无法打开该链接。"
            }, Modifier.testTag("page_trial"), enabled = !saving && link != null) { Text("打开链接核对") }
            if (link != null && visited == link) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(confirmed, { confirmed = it }, Modifier.testTag("page_confirm"), enabled = !saving)
                    Text("我已看到要保存的页面")
                }
            }
            message?.let { Text(it, color = DockColors.Error) }
            Button({
                val target = link ?: return@Button
                saving = true
                scope.launch {
                    if (save(title, PageTarget(target, System.currentTimeMillis()))) onClose()
                    else message = "页面卡未保存，请重试。"
                    saving = false
                }
            }, Modifier.fillMaxWidth().testTag("page_save"), enabled = confirmed && !saving && title.isNotBlank()) {
                Text("保存到空间")
            }
        }
    }
}

/** 页面卡没有任务字段。保留确认时间，打开时由来源应用或浏览器恢复其当前状态。 */
@Composable
internal fun PageCardContent(page: PageTarget, setCover: (String) -> Unit) {
    val context = LocalContext.current
    val preview = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.getStringExtra("cover_uri")?.let(setCover)
    }
    var unavailable by remember { mutableStateOf(false) }
    Text(Uri.parse(page.url).host.orEmpty(), style = MaterialTheme.typography.titleLarge)
    Text("确认于 ${formatTimestamp(page.verifiedAt)}", style = MaterialTheme.typography.bodySmall, color = DockColors.Muted)
    Button({ unavailable = !openPage(context, page.url) }, Modifier.testTag("open_saved_page")) { Text("打开来源链接") }
    if (unavailable) Text("设备无法打开该链接。", color = DockColors.Error)
    if (context.livePreviewAvailable()) OutlinedButton({
        preview.launch(context.livePreviewIntent(page.url).putExtra("cover_capture", true))
    }) {
        Text("在卡坞内打开 · 实验")
    }
}

@Composable
internal fun CardCoverSettings(card: CardRecord, setCover: (String?) -> Unit) {
    val context = LocalContext.current
    var error by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                setCover(uri.toString())
                error = false
            } catch (_: SecurityException) {
                error = true
            }
        }
    }
    val cover = rememberCover(card.coverUri)
    if (cover != null) Image(cover, if (card.coverUri?.startsWith("file:") == true) "来源页面封面" else "自选封面", Modifier.fillMaxWidth().height(150.dp), contentScale = ContentScale.Crop)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ pick.launch(arrayOf("image/*")) }, Modifier.testTag("choose_cover")) { Text("选择封面") }
        if (card.coverUri != null) TextButton({ setCover(null) }) { Text("移除封面") }
    }
    if (error) Text("未取得图片读取授权。", color = DockColors.Error)
}

/** 解码放在 IO 线程；按封面用途缩小大图，文档已移除时回到默认展示。 */
@Composable
internal fun rememberCover(coverUri: String?): ImageBitmap? {
    val resolver = LocalContext.current.contentResolver
    val bitmap = remember(coverUri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(coverUri) {
        val uri = coverUri?.let(Uri::parse) ?: return@LaunchedEffect
        bitmap.value = withContext(Dispatchers.IO) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                val sampling = BitmapFactory.Options().apply {
                    while (maxOf(bounds.outWidth, bounds.outHeight) / (inSampleSize.coerceAtLeast(1)) > 1200) {
                        inSampleSize = inSampleSize.coerceAtLeast(1) * 2
                    }
                }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, sampling)?.asImageBitmap() }
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
        }
    }
    return bitmap.value
}
