package com.wangzan101.mika.record

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.wangzan101.mika.core.record.Redactor
import com.wangzan101.mika.core.record.RunMetrics
import com.wangzan101.mika.core.record.RunMetricsStore
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunRecords
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.record.RunSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.io.FileNotFoundException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

private const val TAG = "RunStore"
private const val MAX_WIDTH = 720
private const val JPEG_QUALITY = 70

/**
 * 运行记录落盘:`files/runs/<yyyyMMdd-HHmmss>-<6位>/` 下 run.json(概要)、events.jsonl(一行一个事件)、
 * screens/NNNN.jpg(读屏画面)。只保留最近 [keep] 次。写盘出错只记日志,不影响任务。
 * 每次运行的小结([RunMetrics])另存在 `files/run-metrics.jsonl`([metrics]),不随记录清理。
 */
class RunStore internal constructor(
    filesDir: File,
    private val externalDir: () -> File?,
    private val keep: Int,
    private val clock: () -> Long,
    private val encode: (ByteArray) -> ByteArray?,
) {
    constructor(context: Context, keep: Int = 30) : this(
        context.applicationContext.filesDir,
        { context.applicationContext.getExternalFilesDir(null) },
        keep,
        System::currentTimeMillis,
        ::toJpeg,
    )

    init { require(keep > 0) { "keep must be positive" } }

    private val root = File(filesDir, "runs")

    /** 取相近需求在同一应用确实点过的标签，旧答复和数值不进入新任务。 */
    fun navigationLabels(goal: String, pkg: String): List<String> {
        if (pkg.isBlank()) return emptyList()
        fun pairs(text: String) = text.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit).windowed(2).toSet()
        val wanted = pairs(goal)
        val related = list().filter { it.outcome in setOf("succeeded", "unverified") }.map { run ->
            val previous = pairs(run.goal)
            run to (wanted.intersect(previous).size * 2.0 / (wanted.size + previous.size).coerceAtLeast(1))
        }.filter { it.second >= 0.70 }.sortedByDescending { it.second }
        val entrances = linkedSetOf<String>()
        for ((run) in related) {
            val events = events(run.id)
            if (events.filterIsInstance<RunEvent.OutcomeJudged>().none { it.outcome == "done" }) continue
            val recorded = events.filterIsInstance<RunEvent.ActionTarget>()
                .filter { it.effective && !it.protected && it.tool == "phone_tap" && it.before.packageName == pkg }
                .mapNotNull { it.target?.name?.trim() }.filter { it.length in 2..24 && it.none(Char::isDigit) }
                .distinct().take(6)
            entrances.addAll(recorded)
            // 较早版本没有动作锚点，只采用能与原应用快照对上的成功点击。
            val taps = events.filterIsInstance<RunEvent.ModelReply>().filter { it.tool == "phone_tap" }.associateBy { it.step }
            var screen: org.json.JSONObject? = null
            val names = linkedSetOf<String>()
            for (event in events.filterIsInstance<RunEvent.ToolResult>()) {
                val data = runCatching { org.json.JSONObject(event.resultJson) }.getOrNull() ?: continue
                val prior = screen
                if (event.tool == "phone_tap" && prior?.optString("currentApp") == pkg && data.optBoolean("ok") && data.optBoolean("changed")) {
                    val args = taps[event.step]?.argsJson?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
                    if (args != null && args.optString("snapshotId").isNotEmpty() && args.optString("snapshotId") == prior.optString("snapshotId")) {
                        val elements = prior.optJSONArray("elements")
                        val target = (0 until (elements?.length() ?: 0)).mapNotNull { elements?.optJSONObject(it) }
                            .firstOrNull { it.optString("ref") == args.optString("ref") }
                        // 无文字图块也可能有经过截图定位的名称。只取该次成功点击说明中的短引文，供新任务现场核实。
                        val name = target?.optString("name")?.trim()?.takeIf { it.length in 2..24 && it.none(Char::isDigit) }
                        if (name != null) names.add(name) else if (target != null) {
                            val title = Regex("[「“]([^」”]{2,24})[」”]").find(args.optString("reason"))?.groupValues?.get(1)
                            val center = target.optJSONObject("center")
                            if (title != null && title.none(Char::isDigit) && center != null && center.optInt("x", -1) in 0..999 && center.optInt("y", -1) in 0..999) {
                                names.add("$title（旧图块中心约横向${center.optInt("x") / 10}%、纵向${center.optInt("y") / 10}%，请对照当前图块）")
                            }
                        }
                    }
                }
                if (event.tool == "phone_launch_app") screen = null
                val next = data.optJSONObject("screen") ?: data
                if (next.has("elements")) screen = next
            }
            // 从启动页试查时，优先用同样从启动页开始的一条完整记录，保留其中的点击次序。
            val launch = events.filterIsInstance<RunEvent.ActionTarget>().firstOrNull { it.tool == "phone_launch_app" && it.effective }
            val entry = launch?.let { runCatching { org.json.JSONObject(it.argsJson) }.getOrNull() }
            if (names.size >= 2 && entry?.optString("package") == pkg && entry.optBoolean("cold")) return names.take(6)
            entrances.addAll(names)
        }
        return entrances.take(6)
    }

    /** 每次运行的小结;运行记录关上时由写线程记一份(见 [RunHandle])。 */
    val metrics = RunMetricsStore(File(filesDir, RunMetricsStore.FILE_NAME))

    private var livePickup: Pair<String, PickupLiveEvidence>? = null

    /** 只有创建该次运行的 TaskRun 能读到这一份临时证据。历史时间线始终读取已脱敏的文件。 */
    fun pickupEvidence(runId: String): List<RunEvent>? =
        livePickup?.takeIf { it.first == runId }?.second?.evidence()

    fun open(goal: String): RunHandle {
        val start = clock()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(start))
        val suffix = (1..6).map { ALPHABET[Random.nextInt(ALPHABET.length)] }.joinToString("")
        val id = "$stamp-$suffix"
        val dir = File(root, id)
        dir.mkdirs()
        val pickup = if (PickupDiagnostics.applies(goal)) PickupLiveEvidence() else null
        livePickup = pickup?.let { id to it }
        val title = Redactor.text(goal).let { if (pickup == null) it else PickupDiagnostics.text(it) }
        val handle = RunHandle(id, dir, RunSummary(id = id, goal = title, startedAtMs = start), encode, metrics::append, pickup)
        prune()
        return handle
    }

    /** 新的在前。 */
    fun list(): List<RunSummary> = runDirs().reversed().mapNotNull { d ->
        runCatching { RunEvent.json.decodeFromString(RunSummary.serializer(), File(d, RUN_JSON).readText()) }.getOrNull()
    }

    /** 一次运行的概要;记录已被清掉或读不出为 null。 */
    fun summary(runId: String): RunSummary? = dirOf(runId)?.let { d ->
        runCatching { RunEvent.json.decodeFromString(RunSummary.serializer(), File(d, RUN_JSON).readText()) }.getOrNull()
    }

    /** 只读地交给核心(查这个对话之前几轮的记录,见 history_read);在 IO 线程上读。 */
    fun reader(): RunRecords = object : RunRecords {
        override suspend fun summary(runId: String) = withContext(Dispatchers.IO) { this@RunStore.summary(runId) }
        override suspend fun events(runId: String) = withContext(Dispatchers.IO) { this@RunStore.events(runId) }
    }

    fun events(runId: String): List<RunEvent> {
        val f = File(dirOf(runId) ?: return emptyList(), EVENTS)
        if (!f.isFile) return emptyList()
        return f.readLines().filter { it.isNotBlank() }.mapNotNull { runCatching { RunEvent.decode(it) }.getOrNull() }
    }

    /** 从已有 JSONL 按原顺序取用量字段。旧记录的空值、同一步的多次回复和没有步号的压缩事件原样保留。 */
    fun usageLog(runId: String): String {
        val dir = dirOf(runId) ?: throw FileNotFoundException("Run is unavailable")
        return File(dir, EVENTS).useLines { lines ->
            buildString {
                for ((index, line) in lines.withIndex()) {
                    if (line.isBlank()) continue
                    val event = try {
                        RunEvent.json.parseToJsonElement(line) as? JsonObject
                    } catch (_: SerializationException) { null }
                    val type = event?.get("type") as? JsonPrimitive
                    if (event == null || type == null) {
                        // 进程中断可能留下一行没写完的记录。标出缺口，保留其余事件。
                        appendLine("""{"type":"unreadable_event","line":${index + 1}}""")
                        continue
                    }
                    val fields = USAGE_FIELDS[type.content] ?: continue
                    val metadata = event.filter { (key, value) ->
                        value is JsonPrimitive && (key == "type" || key == "atMs" || key in fields)
                    }
                    appendLine(JsonObject(metadata).toString())
                }
            }.ifEmpty { throw IOException("No run usage events") }
        }
    }

    /**
     * 运行记录关上之后补记一个事件(交出回答后判的结局):追加到 events.jsonl,并计入 run.json 的概要。
     * 记录已被清掉或写盘失败只记日志,不影响任务。
     */
    @Synchronized
    fun append(runId: String, e: RunEvent) {
        val dir = dirOf(runId) ?: return
        runCatching {
            val summaryFile = File(dir, RUN_JSON)
            val old = RunEvent.json.decodeFromString(RunSummary.serializer(), summaryFile.readText())
            val saved = if (PickupDiagnostics.applies(old.goal)) PickupDiagnostics.event(e) else e
            File(dir, EVENTS).appendText(RunEvent.encode(saved) + "\n")
            val summary = old.with(saved)
            val tmp = File(dir, "$RUN_JSON.tmp")
            tmp.writeText(RunEvent.json.encodeToString(RunSummary.serializer(), summary))
            Files.move(tmp.toPath(), summaryFile.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            if (e is RunEvent.OutcomeJudged) metrics.update(runId) { it.copy(judged = e.outcome) }
        }.onFailure { runCatching { Log.w(TAG, "could not append to run $runId", it) } }
    }

    fun screen(runId: String, id: Int): File? = dirOf(runId)?.let { File(it, screenPath(id)) }?.takeIf { it.isFile }

    /** 复制到外部文件目录的 runs/ 下,便于拉到开发机(调试版和评测用);那边同样只留最近 [keep] 次。 */
    fun mirrorToExternal(runId: String) {
        val ext = File(externalDir() ?: return, "runs")
        if (copyTo(runId, File(ext, runId))) runCatching { dirsIn(ext).dropLast(keep).forEach { it.deleteRecursively() } }
    }

    /** 把一次运行的整个目录复制到 [dest](评测把每题的记录放到自己的结果目录下)。 */
    fun copyTo(runId: String, dest: File): Boolean {
        val src = dirOf(runId) ?: return false
        return runCatching { src.copyRecursively(dest, overwrite = true) }
            .onFailure { runCatching { Log.w(TAG, "could not copy run $runId", it) } }
            .getOrDefault(false)
    }

    /** 只接受本目录下真实存在的运行,防止拼出别的路径。 */
    private fun dirOf(runId: String): File? =
        File(root, runId).takeIf { ID_RE.matches(runId) && it.isDirectory }

    private fun runDirs(): List<File> = dirsIn(root)

    private fun dirsIn(parent: File): List<File> =
        parent.listFiles { f -> f.isDirectory && ID_RE.matches(f.name) }.orEmpty().sortedBy { it.name }

    private fun prune() {
        runDirs().dropLast(keep).forEach { it.deleteRecursively() }
    }

    companion object {
        internal const val RUN_JSON = "run.json"
        internal const val EVENTS = "events.jsonl"
        private val USAGE_FIELDS = mapOf(
            "task_started" to setOf("model"),
            "model_reply" to setOf("step", "model", "role", "tool", "inputTokens", "cachedTokens",
                "outputTokens", "repliedAtMs", "latencyMs", "retryWaitMs"),
            "compressed" to setOf("foldedSteps"),
            "model_calls" to setOf("calls", "httpCalls", "unreported", "httpUnreported", "inputTokens", "cachedTokens", "outputTokens"),
            "task_ended" to setOf("outcome", "kind"),
        )
        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
        private val ID_RE = Regex("""\d{8}-\d{6}-[a-z0-9]{6}""")

        internal fun screenPath(id: Int) = "screens/%04d.jpg".format(Locale.US, id)
    }
}

/**
 * 一次运行的记录;任务结束时 [close]。
 * 写盘和压图都在一个专用线程上做,[event]/[screen] 只入队、从不阻塞 Agent 循环;小结([RunMetrics])也在这个线程上边写边算,
 * 写完最后一个事件交给 [finished](跑起来过的运行才有);
 * 画面积压超过 [MAX_PENDING_FRAMES] 张时丢最旧的。[close] 会等队列写完(最多 [CLOSE_WAIT_MS])。
 */
class RunHandle internal constructor(
    val runId: String,
    private val dir: File,
    private var summary: RunSummary,
    private val encode: (ByteArray) -> ByteArray?,
    private val finished: (RunMetrics) -> Unit = {},
    private val pickup: PickupLiveEvidence? = null,
) : RunSink, Closeable {
    private val lock = Object()
    private val events = ArrayDeque<RunEvent>()
    private val frames = ArrayDeque<Pair<Int, ByteArray>>()
    private var closing = false
    private var sinceSummary = 0
    private val metrics = RunMetrics.Builder(runId)
    private val writer = Thread(::drain, "mika-run-$runId").apply { isDaemon = true }

    init {
        writeSummary()
        writer.start()
    }

    override fun event(e: RunEvent) {
        synchronized(lock) {
            if (closing) return
            pickup?.observe(e)
            events.addLast(e)
            lock.notifyAll()
        }
    }

    override fun media(mediaId: Int, png: ByteArray) {
        synchronized(lock) {
            if (closing) return
            if (pickup != null) return
            if (frames.size >= MAX_PENDING_FRAMES) frames.removeFirst()
            frames.addLast(mediaId to png)
            lock.notifyAll()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closing) return
            closing = true
            lock.notifyAll()
        }
        writer.join(CLOSE_WAIT_MS)
    }

    private fun drain() {
        while (true) {
            var batch: List<RunEvent> = emptyList()
            var frame: Pair<Int, ByteArray>? = null
            var done = false
            synchronized(lock) {
                while (events.isEmpty() && frames.isEmpty() && !closing) lock.wait()
                if (events.isEmpty() && frames.isEmpty()) {
                    writeSummary()
                    done = true
                } else {
                    batch = events.toList().also { events.clear() }
                    // 事件优先;画面一次一张,中间回来看有没有新事件
                    frame = if (batch.isEmpty()) frames.removeFirstOrNull() else null
                }
            }
            if (done) {
                // 小结在锁外写:写的是另一个文件,不挡着别的线程
                metrics.build()?.let { m -> safely("metrics") { finished(m) } }
                return
            }
            batch.forEach { write(if (pickup == null) it else PickupDiagnostics.event(it)) }
            frame?.let { (id, png) -> writeFrame(id, png) }
        }
    }

    private fun write(e: RunEvent) {
        // 先计入概要再追加,即使追加失败,结局也会写进 run.json
        summary = summary.with(e)
        metrics.add(e)
        safely("event") { File(dir, RunStore.EVENTS).appendText(RunEvent.encode(e) + "\n") }
        sinceSummary += 1
        // 定期刷新概要:进程被杀时 run.json 里也有大致的步数和用量
        if (e is RunEvent.TaskEnded || sinceSummary >= SUMMARY_EVERY) writeSummary()
    }

    private fun writeFrame(id: Int, png: ByteArray) {
        val jpg = safely("screen") { encode(png) } ?: return
        safely("screen") {
            val f = File(dir, RunStore.screenPath(id))
            f.parentFile?.mkdirs()
            f.writeBytes(jpg)
        }
    }

    private fun writeSummary() {
        sinceSummary = 0
        safely("summary") {
            val tmp = File(dir, "${RunStore.RUN_JSON}.tmp")
            tmp.writeText(RunEvent.json.encodeToString(RunSummary.serializer(), summary))
            Files.move(tmp.toPath(), File(dir, RunStore.RUN_JSON).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }
    }

    private inline fun <T> safely(what: String, block: () -> T): T? = try {
        block()
    } catch (e: Throwable) {
        runCatching { Log.w(TAG, "could not write run $what", e) }
        null
    }

    internal companion object {
        const val MAX_PENDING_FRAMES = 4
        const val SUMMARY_EVERY = 10
        const val CLOSE_WAIT_MS = 10_000L
    }
}

/** 先只读尺寸,按 2 的幂降采样解码,再缩到宽 ≤ 720,JPEG 质量 70。 */
private fun toJpeg(png: ByteArray): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= MAX_WIDTH) sample *= 2
    val src = BitmapFactory.decodeByteArray(png, 0, png.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val scaled = if (src.width <= MAX_WIDTH) src
    else Bitmap.createScaledBitmap(src, MAX_WIDTH, (src.height.toLong() * MAX_WIDTH / src.width).toInt().coerceAtLeast(1), true)
    return try {
        ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
    } finally {
        if (scaled !== src) scaled.recycle()
        src.recycle()
    }
}
