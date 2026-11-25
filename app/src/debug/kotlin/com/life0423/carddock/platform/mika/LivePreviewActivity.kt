package com.life0423.carddock.platform.mika

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.view.MotionEvent
import android.view.Surface as DisplaySurface
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.life0423.carddock.ui.theme.CardDockTheme
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 用户手动选择调试目标。预览接管、操作、收起和原应用交接均不需要模型。 */
class LivePreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 预览可能包含个人页面；最后一帧只保存在进程内存中。
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val requestedPackage = intent.getStringExtra("target_package")
        val url = intent.getStringExtra("target_url")
        val choices = availableTargets(requestedPackage, url)
        val allowCoverCapture = intent.getBooleanExtra("cover_capture", false)
        setContent {
            CardDockTheme {
                Surface(Modifier.fillMaxSize()) {
                    if (Build.VERSION.SDK_INT < 34) {
                        Column(Modifier.safeDrawingPadding().padding(20.dp)) {
                            Text("实时预览需要 Android 14 或更新版本。")
                            TextButton(::finish) { Text("返回") }
                        }
                    } else {
                        Experiment(choices, allowCoverCapture)
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun availableTargets(requestedPackage: String?, url: String?): List<PreviewTarget> {
        val source = if (url == null) Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER) else {
            val uri = Uri.parse(url)
            if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) return emptyList()
            Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        }
        if (requestedPackage != null) source.setPackage(requestedPackage)
        val flags = if (url == null) 0 else android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
        return packageManager.queryIntentActivities(source, flags)
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { PreviewTarget(it.activityInfo.packageName, it.loadLabel(packageManager).toString(), url) }
            .sortedBy { it.label }
    }

    @Composable
    private fun Experiment(choices: List<PreviewTarget>, allowCoverCapture: Boolean) {
        val helper by LivePreviewHandoff.display.collectAsStateWithLifecycle()
        var target by remember { mutableStateOf(PreviewMemory.target?.takeIf(choices::contains)) }
        var retry by remember { mutableIntStateOf(0) }

        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            val selected = target
            if (selected == null) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("实时预览实验", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                            TextButton(::finish) { Text("返回") }
                        }
                        if (helper == null) {
                            Text("用 USB 或无线调试授权此设备，在电脑运行 tools/start-live-preview.sh <设备序列号>。")
                            Text("等待调试连接", style = MaterialTheme.typography.labelLarge)
                        } else {
                            TextButton({
                                PreviewMemory.end()
                                helper?.call { stopHelper(); true }
                            }) { Text("断开调试助手") }
                        }
                        if (choices.isEmpty()) Text("此设备没有可打开目标的应用。")
                    }
                    items(choices, key = { it.packageName }) { entry ->
                        TextButton(onClick = {
                            if (PreviewMemory.target != entry) {
                                PreviewMemory.end()
                                PreviewMemory.target = entry
                            }
                            target = entry
                        }, enabled = helper != null, modifier = Modifier.fillMaxWidth().testTag("preview_app_${entry.packageName}")) {
                            Text(entry.label, Modifier.fillMaxWidth())
                        }
                    }
                }
            } else {
                key(selected, retry, helper) {
                    Preview(
                        selected, helper, allowCoverCapture,
                        onChoose = { PreviewMemory.end(); target = null },
                        onRetry = { PreviewMemory.end(keepFrame = true); retry++ },
                    )
                }
            }
        }
    }

    @Composable
    private fun ColumnScope.Preview(
        target: PreviewTarget,
        helper: ILivePreviewDisplay?,
        allowCoverCapture: Boolean,
        onChoose: () -> Unit,
        onRetry: () -> Unit,
    ) {
        var view by remember { mutableStateOf<PreviewTexture?>(null) }
        var live by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        var savingCover by remember { mutableStateOf(false) }
        var showConnectionHelp by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val texture = view
        BackHandler {
            if (!live || PreviewMemory.running?.back() != true) finish()
        }
        LaunchedEffect(texture, helper) {
            if (texture == null || helper == null) return@LaunchedEffect
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val start = android.os.SystemClock.uptimeMillis()
                while (true) {
                    val running = PreviewMemory.running
                    val result = withContext(Dispatchers.IO) {
                        running?.helper?.call { showsTarget(running.id) } == true
                    }
                    val wasLive = live
                    live = result && texture.frameTime > 0
                    if (live) {
                        if (!wasLive) message = null
                        val stamp = texture.frameTime
                        if (stamp > (PreviewMemory.lastFrame?.time ?: 0)) {
                            texture.capture(256)?.let { PreviewMemory.lastFrame = PreviewFrame(it, stamp) }
                        }
                    } else if (message == null && android.os.SystemClock.uptimeMillis() - start > 5_000) {
                        message = "目标应用暂未显示。"
                    }
                    delay(700)
                    if (PreviewMemory.running == null && message != null) break
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(target.label, Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.titleMedium)
            TextButton(::finish) { Text("收起") }
            TextButton({
                val running = PreviewMemory.running
                if (running?.helper?.call { openOnMainDisplay(running.id) } == true) {
                    PreviewMemory.end()
                    finish()
                } else {
                    message = "无法移交当前页面，仍留在预览。"
                }
            }, enabled = live) { Text("在原应用打开") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({
                if (PreviewMemory.running?.back() != true) message = "当前页面无法返回。"
            }, enabled = live) { Text("返回") }
            TextButton(onChoose) { Text("结束预览") }
            if (allowCoverCapture && live) TextButton(
                enabled = !savingCover,
                onClick = {
                    val running = PreviewMemory.running
                    val bitmap = if (running?.helper?.call { showsTarget(running.id) } == true) texture?.capture(720) else null
                    if (bitmap == null) {
                        message = "当前画面无法保存为封面。"
                    } else {
                        savingCover = true
                        scope.launch {
                            val file = withContext(Dispatchers.IO) {
                                val directory = File(filesDir, "mika-covers").apply { mkdirs() }
                                val destination = File(directory, "${UUID.randomUUID()}.png")
                                try {
                                    destination.outputStream().use {
                                        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) {
                                            throw IOException("Unable to encode preview")
                                        }
                                    }
                                    destination
                                } catch (_: IOException) {
                                    destination.delete()
                                    null
                                }
                            }
                            savingCover = false
                            if (file == null) {
                                message = "封面保存失败，请重试。"
                            } else {
                                setResult(RESULT_OK, Intent().putExtra("cover_uri", Uri.fromFile(file).toString()))
                                finish()
                            }
                        }
                    }
                },
            ) { Text("保存为封面") }
            if (!live) {
                if (helper == null) TextButton({ showConnectionHelp = true }) { Text("恢复连接") }
                else TextButton(onRetry) { Text("恢复") }
            }
        }
        if (showConnectionHelp) AlertDialog(
            onDismissRequest = { showConnectionHelp = false },
            title = { Text("恢复调试连接") },
            text = { Text("在电脑运行 tools/start-live-preview.sh <设备序列号>。旧画面会保留到重新看到来源应用。") },
            confirmButton = { TextButton({ showConnectionHelp = false }) { Text("知道了") } },
        )

        Box(Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (helper != null) AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    PreviewTexture(
                        activity = this@LivePreviewActivity,
                        target = target,
                        helper = helper,
                        onFailure = { message = it },
                        onLost = { live = false },
                    ).also { view = it }
                },
                update = { it.allowInput = live },
            )
            if (!live) {
                val snapshot = PreviewMemory.lastFrame?.takeIf { PreviewMemory.target == target }
                Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
                    if (snapshot != null) Image(snapshot.bitmap.asImageBitmap(), contentDescription = "离线画面",
                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
                Surface(Modifier.align(Alignment.TopCenter)) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                        Text(
                            when {
                                snapshot != null -> "快照 · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                                    .format(Date(snapshot.time))
                                helper == null -> "调试连接已断开"
                                message != null -> requireNotNull(message)
                                else -> "正在打开"
                            },
                            style = MaterialTheme.typography.labelLarge,
                        )
                        if (message == null && snapshot == null && helper != null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            } else if (message != null) {
                Surface(Modifier.align(Alignment.TopCenter)) { Text(requireNotNull(message), Modifier.padding(8.dp)) }
            }
        }
    }
}

private data class PreviewTarget(val packageName: String, val label: String, val url: String?)
private data class PreviewFrame(val bitmap: Bitmap, val time: Long)

/** 在同一进程内保留一个实验会话。收起释放本地 Surface，原任务留在同一个助手屏。 */
private object PreviewMemory {
    var target: PreviewTarget? = null
    var running: PreviewSession? = null
    var lastFrame: PreviewFrame? = null

    fun end(keepFrame: Boolean = false) {
        running?.let { session -> session.helper.call { closePreview(session.id); true } }
        running = null
        if (!keepFrame) {
            target = null
            lastFrame = null
        }
    }
}

private class PreviewSession(
    val helper: ILivePreviewDisplay,
    val id: Int,
    val width: Int,
    val height: Int,
) {
    var viewer: PreviewTexture? = null
    fun back(): Boolean = helper.call { back(id) } == true
}

private class PreviewTexture(
    activity: ComponentActivity,
    private val target: PreviewTarget,
    private val helper: ILivePreviewDisplay,
    private val onFailure: (String) -> Unit,
    private val onLost: () -> Unit,
) : TextureView(activity), TextureView.SurfaceTextureListener {
    private var output: DisplaySurface? = null
    var allowInput = false
    var frameTime: Long = 0
        private set

    init {
        surfaceTextureListener = this
        setOnTouchListener { _, event ->
            val session = PreviewMemory.running
            if (!allowInput || session?.viewer !== this) return@setOnTouchListener false
            val scaled = MotionEvent.obtain(event)
            try {
                scaled.transform(Matrix().apply { setScale(session.width.toFloat() / width, session.height.toFloat() / height) })
                helper.call { touch(session.id, scaled); true } == true
            } finally {
                scaled.recycle()
            }
        }
    }

    /** 只截取当前预览纹理，保留比例；系统标记为安全的来源窗口由 Android 隐去。 */
    fun capture(maxWidth: Int): Bitmap? {
        if (!isAvailable || width == 0) return null
        val outputWidth = width.coerceAtMost(maxWidth)
        val outputHeight = (height.toLong() * outputWidth / width).toInt().coerceAtLeast(1)
        return getBitmap(outputWidth, outputHeight)
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        val surface = DisplaySurface(texture).also { output = it }
        val previous = PreviewMemory.running
        if (previous != null && previous.helper.asBinder() == helper.asBinder() && PreviewMemory.target == target &&
            helper.call { attachSurface(previous.id, surface) } == true
        ) {
            previous.viewer = this
            return
        }
        PreviewMemory.end(keepFrame = true)
        PreviewMemory.target = target
        val result = helper.call { openPreview(surface, width, height, resources.displayMetrics.densityDpi,
            target.packageName, target.url) }
        if (result == null || result < 0) {
            onFailure(if (result == -2) "目标应用正在使用主屏，收起后重试。" else "无法启动实验预览。")
        } else {
            PreviewMemory.running = PreviewSession(helper, result, width, height).also { it.viewer = this }
        }
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        PreviewMemory.running?.takeIf { it.viewer === this }?.let { session ->
            session.viewer = null
            session.helper.call { attachSurface(session.id, null) }
        }
        output?.release()
        output = null
        onLost()
        return true
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) { frameTime = System.currentTimeMillis() }
}

/** 只处理出现频率可见的断连，权限或参数错误留给相应入口报告。 */
private inline fun <T> ILivePreviewDisplay.call(remote: ILivePreviewDisplay.() -> T): T? = try {
    remote()
} catch (_: RemoteException) {
    null
}
