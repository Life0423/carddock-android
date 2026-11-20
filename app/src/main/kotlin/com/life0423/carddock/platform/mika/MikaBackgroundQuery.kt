package com.life0423.carddock.platform.mika

import com.life0423.carddock.core.model.PickupEntry
import com.life0423.carddock.core.rule.CountRoute
import com.life0423.carddock.platform.orderActionBounds
import com.life0423.carddock.platform.FILTER_APPEARANCE_REQUIRED
import com.life0423.carddock.platform.nextOrderWindow
import com.life0423.carddock.platform.CountField
import com.life0423.carddock.platform.readVisibleCount
import com.life0423.carddock.core.rule.ReminderRoute
import com.life0423.carddock.platform.UpcomingReminder
import android.content.Context
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.RemoteException
import android.view.Surface
import com.life0423.carddock.core.port.DeviceResponse
import com.life0423.carddock.platform.CAINIAO_PACKAGE
import com.life0423.carddock.platform.MEITUAN_PACKAGE
import com.life0423.carddock.platform.CainiaoDeviceGateway
import com.life0423.carddock.platform.CainiaoPage
import com.life0423.carddock.platform.CardDockAccessibilityService
import com.life0423.carddock.platform.LatestTakeout
import com.life0423.carddock.platform.MeituanGateway
import com.life0423.carddock.platform.classifyCainiaoPage

/** 在 IO 线程启动可信虚拟屏，使用现有规则适配器读取并在结束时释放该屏。 */
internal class MikaBackgroundQuery(private val context: Context, private val focused: () -> Boolean) {
    suspend fun cainiao(sourceId: String, targetKey: String): DeviceResponse = query(
        "菜鸟",
        { shell, surface, width, height, density -> shell.openCainiao(surface, width, height, density) },
    ) { reader, id, connected, _ ->
        var openedPickup = false
        CainiaoDeviceGateway(
            connected = connected,
            observe = {
                val page = reader.observe(id)
                if (!openedPickup && page?.labels?.contains("取包裹") == true &&
                    classifyCainiaoPage(page) == CainiaoPage.UNKNOWN) {
                    openedPickup = reader.clickBackgroundLabel(id, CAINIAO_PACKAGE, "取包裹")
                }
                page
            },
            disconnected = "卡坞离开前台或页面读取服务断开，已停止查询。",
            missing = if (targetKey == com.life0423.carddock.platform.CAINIAO_STATUS_TARGET) "未读到明确的待取件数或空状态。请在菜鸟核对后重试。" else "后台屏未核验到菜鸟待取空状态。",
        ).inspect(sourceId, targetKey)
    }

    // query 在 API 34 以下直接返回，observe 只会在支持后台显示屏的设备执行。
    @android.annotation.SuppressLint("NewApi")
    suspend fun latestTakeout(): DeviceResponse = query(
        "美团最近外卖", { shell, surface, w, h, dpi -> shell.openMeituanOrders(surface, w, h, dpi) }, tall = true, checkFilters = true,
    ) { reader, id, connected, frame ->
        LatestTakeout(connected,
            observe = { reader.clearCache(); reader.observeDetail(MEITUAN_PACKAGE, id, geometry = true) },
            tap = { node -> connected() && reader.tapReadTarget(id, MEITUAN_PACKAGE, node) },
            selection = { node -> frame.selection(node) },
            expand = { reader.clickBackgroundLabel(id, MEITUAN_PACKAGE, "配送骑手/支付方式等") },
        ).inspect()
    }

    /** 本机兼容性试查。仅观察本次授权的应用，并由系统管理后台屏生命周期。 */
    @android.annotation.SuppressLint("NewApi")
    suspend fun reminderTrial(target: String, route: ReminderRoute? = null,
        learn: ((List<String>) -> List<Int>)? = null, learned: (ReminderRoute) -> Unit = {}): DeviceResponse {
        val agenda = UpcomingReminder(target, route, learn, learned)
        do {
            val result = query("提醒来源", { shell, surface, w, h, dpi -> shell.openLauncher(surface, w, h, dpi, target) }) {
                reader, id, connected, _ ->
                agenda.readOne(connected,
                    observe = { reader.clearCache(); reader.observeDetail(target, id, geometry = true) },
                    tap = { connected() && reader.tapReadTarget(id, target, it) })
            }
            if (result !is DeviceResponse.Ready || !agenda.hasMore) return result
        } while (true)
    }

    /** 学习与回放都从启动入口打开新的后台屏。读数期间只操作用户指定的导航页签。 */
    @android.annotation.SuppressLint("NewApi") // query 仅在 API 34 及以上建立后台屏。
    suspend fun count(source: String, page: String?, subject: String, saved: CountRoute? = null,
        choose: ((List<CountField>, String) -> CountField?)? = null): DeviceResponse =
        query("数量来源", { helper, surface, w, h, dpi -> helper.openLauncher(surface, w, h, dpi, source) }) { reader, id, connected, _ ->
            readVisibleCount(source, page, subject, saved, connected,
                observe = { reader.clearCache(); reader.observeDetail(source, id, geometry = true) },
                tap = { node, cell -> connected() && reader.tapReadTarget(id, source, node, cell) }, choose = choose)
        }

    /** 每笔详情单独建立后台屏；同一查询的路线、跨页清单和截止点保存在 scan。 */
    @android.annotation.SuppressLint("NewApi")
    suspend fun monthlyOrders(source: String, scan: com.life0423.carddock.platform.OrderCollection,
        choose: ((List<String>) -> String?)? = null): DeviceResponse {
        do {
            val result = query("订单来源", { helper, surface, w, h, dpi -> helper.openIndependentLauncher(surface,w,h,dpi,source) }, tall = true) {
                reader, id, connected, frame ->
                var lastWindow: com.life0423.carddock.platform.DetailWindow? = null
                scan.runWindow(connected,
                    observe = {
                        reader.clearCache()
                        reader.observeDetail(source,id,geometry=true).also { lastWindow = it }
                    },
                    tap = { node -> connected() && reader.tapReadTarget(id,source,node,
                        lastWindow?.orderActionBounds(node) ?: node.bounds, keepGestureOnLabel = true) },
                    scroll = { connected() && it.bounds?.let { box -> reader.advanceCollection(id,source,box) } == true }, choose,
                    sample = if (scan.compareFilter) frame::buttonColors else null,
                    panelVisible = { frame.enablePanel(scan.compareFilter && it) },
                    tapReceipt = { node -> connected() && reader.tapReadTarget(id,source,node,
                        lastWindow?.orderActionBounds(node) ?: node.bounds, keepGestureOnLabel = true, receiptTarget = true) })
            }
            if (result !== nextOrderWindow) {
                // 此提示由界面映射到单次授权按钮，保持它的协议值。
                if (scan.reconciledCount == 0 || result is DeviceResponse.NeedsUser &&
                    result.message == FILTER_APPEARANCE_REQUIRED) return result
                val progress = " 本次已从付款凭证核对 ${scan.reconciledCount} 笔不同订单。"
                return when (result) {
                    is DeviceResponse.NeedsUser -> result.copy(message = result.message + progress)
                    is DeviceResponse.Failed -> result.copy(message = result.message + progress)
                    is DeviceResponse.Ready -> result
                }
            }
        } while (true)
    }

    /** query 只在 API 34 及以上创建后台屏。每次从官方首页寻找入口，不采样凭证图像。 */
    @android.annotation.SuppressLint("NewApi")
    suspend fun pickupCode(entry: PickupEntry?): DeviceResponse =
        query("菜鸟", { helper,surface,w,h,dpi -> helper.openCainiao(surface,w,h,dpi) }) {
            reader,id,connected,_ ->
            com.life0423.carddock.platform.inspectPickupCode(entry,
                { reader.clearCache(); reader.observe(id,pickup=true) },
                { reader.clickBackgroundLabel(id,CAINIAO_PACKAGE,it.label) },connected)
        }

    suspend fun meituan(oid: String): DeviceResponse = query(
        "美团",
        { shell, surface, width, height, density -> shell.openMeituan(surface, width, height, density, oid) },
        tall = true,
    ) { reader, id, connected, _ ->
        // 此机型过滤应用日志；调试包只覆盖最近一次的脱敏阶段记录。
        val stages = if ((context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            StringBuilder("display-opened\n") else null
        MeituanGateway(
            connected = connected,
            observe = { reader.observeDetail(MEITUAN_PACKAGE, id) },
            trace = stages?.let { { stage -> it.appendLine(stage); Unit } },
            expand = { reader.clickBackgroundLabel(id, MEITUAN_PACKAGE, "配送骑手/支付方式等") },
            disconnected = "卡坞离开前台或页面读取断开，请回到卡坞后重试。",
            missing = "后台屏未读到完整的已完成订单详情，请检查本机路由号后重试。",
        ).inspect().also {
            if (stages != null) try {
                java.io.File(context.cacheDir, "meituan-stages.txt").writeText(stages.toString())
            } catch (_: java.io.IOException) {
                // 调试记录失败仍交付真实查询结果。
            }
        }
    }

    /** [read] 只拿指定屏号读页面。连接或主屏焦点丢失后，等待循环停止。 */
    private suspend fun query(
        name: String,
        open: (ICardDockDisplay, Surface, Int, Int, Int) -> Int,
        tall: Boolean = false,
        checkFilters: Boolean = false,
        read: suspend (CardDockAccessibilityService, Int, () -> Boolean, FilterFrame) -> DeviceResponse,
    ): DeviceResponse {
        if (Build.VERSION.SDK_INT < 34) return DeviceResponse.Failed("此设备不支持独立焦点的后台查询。")
        if (!focused()) return DeviceResponse.NeedsUser("请回到卡坞再查询。")
        val shell = DockMikaHandoff.display.value
            ?: return DeviceResponse.NeedsUser("Mika 助手未连接，请在「我的」查看连接方式。")
        val reader = CardDockAccessibilityService.instance
            ?: return DeviceResponse.NeedsUser("请在「我的」开启卡坞无障碍读取。")
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels.coerceAtMost(1080)
        // 美团展开后需要同时观察状态、号码和时间。用更高的屏幕给同一个详情窗口留足可见区域。
        val height = if (tall) width * 5 / 2 else width * 2
        val sink = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, if (checkFilters) 3 else 2)
        val frames = HandlerThread("carddock-display").apply { start() }
        val filterFrame = FilterFrame(sink.surface, Handler(frames.looper))
        try {
            sink.setOnImageAvailableListener({ it.acquireLatestImage()?.let { image -> if (checkFilters) filterFrame.replace(image) else image.close() } }, Handler(frames.looper))
            val id = open(shell, sink.surface, width, height, metrics.densityDpi * width / metrics.widthPixels)
            if (id == -2) return DeviceResponse.NeedsUser("${name}正在主屏使用，请结束分屏后重试。")
            if (id <= 0) return DeviceResponse.Failed("${name}未能在后台屏打开。")
            try {
                val response = read(reader, id, {
                    focused() && CardDockAccessibilityService.instance === reader && shell.asBinder().isBinderAlive
                }, filterFrame)
                return when {
                    !shell.asBinder().isBinderAlive -> DeviceResponse.Failed("Mika 助手已断开。请重新连接后重试，本次结果未保存。")
                    CardDockAccessibilityService.instance !== reader -> DeviceResponse.NeedsUser("页面读取服务已断开。请在「我的」重新连接后重试，本次结果未保存。")
                    !focused() -> DeviceResponse.Failed("卡坞窗口已失去焦点。请回到卡坞重试，本次结果未保存。")
                    else -> response
                }
            } finally {
                try {
                    shell.closeDisplay(id)
                } catch (_: RemoteException) {
                    // 助手进程退出后，系统销毁它持有的显示屏。
                }
            }
        } catch (_: RemoteException) {
            return DeviceResponse.Failed("Mika 助手已断开，请重新连接后查询。")
        } catch (_: SecurityException) {
            return DeviceResponse.Failed("设备未允许 Mika 创建独立焦点的后台屏。")
        } finally {
            sink.setOnImageAvailableListener(null, null)
            frames.quitSafely()
            frames.join()
            filterFrame.replace(null)
            sink.close()
        }
    }
}

/** 仅保留后台屏的最近一帧，在两个筛选项文字左侧比较实测按钮底色。图像不落盘。 */
private class FilterFrame(private val surface: Surface, private val handler: Handler) {
    private var latest: android.media.Image? = null

    @Synchronized fun replace(image: android.media.Image?) {
        latest?.close()
        latest = image
    }

    @Volatile private var panel = false

    fun enablePanel(on: Boolean) {
        panel = on
    }

    /** 静止面板不会持续产帧。按需复制已确认的两个文字框，不保留整屏图片。 */
    suspend fun buttonColors(nodes: List<com.life0423.carddock.platform.DetailNode>): List<Int>? {
        if (!panel) return null
        val result = mutableListOf<Int>()
        for (node in nodes) {
            val box = node.bounds ?: return null
            if (box.width() < 8 || box.height() < 4) return null
            val bitmap = android.graphics.Bitmap.createBitmap(box.width(), box.height(), android.graphics.Bitmap.Config.ARGB_8888)
            val color = kotlinx.coroutines.suspendCancellableCoroutine<Int?> { continuation ->
                android.view.PixelCopy.request(surface, box, bitmap, { code ->
                    val value = if (panel && continuation.isActive && code == android.view.PixelCopy.SUCCESS)
                        buttonAppearance(bitmap) else null
                    bitmap.recycle()
                    if (continuation.isActive) continuation.resumeWith(Result.success(value))
                }, handler)
            } ?: return null
            result += color
        }
        return result
    }

    private fun buttonAppearance(bitmap: android.graphics.Bitmap): Int {
        val frequencies = mutableMapOf<Int,Int>()
        for (y in 1 until bitmap.height-1 step 2) {
            for (x in 1 until bitmap.width-1 step 2) {
                val pixel = bitmap.getPixel(x,y)
                val color = ((pixel shr 12) and 0xf00) or ((pixel shr 8) and 0xf0) or ((pixel shr 4) and 0xf)
                frequencies[color] = (frequencies[color] ?: 0) + 1
            }
        }
        val colors = frequencies.entries.sortedWith(compareByDescending<Map.Entry<Int,Int>> { it.value }.thenBy { it.key })
        return colors.first().key * 4096 + (colors.getOrNull(1)?.key ?: colors.first().key)
    }

    @Synchronized fun selection(node: com.life0423.carddock.platform.DetailNode): Boolean? =
        when (sampleColor(node)) {
            0xfffadb -> true
            0xffffff -> false
            else -> null
        }

    private fun sampleColor(node: com.life0423.carddock.platform.DetailNode): Int? {
        val image = latest ?: return null
        val box = node.bounds ?: return null
        val plane = image.planes.first()
        val y = box.centerY()
        val positions = listOf(box.left-4,box.left-8,box.left-12)
        val colors = positions.map { x ->
            if (x !in 0 until image.width || y !in 0 until image.height) return null
            val pos = plane.rowStride * y + plane.pixelStride * x
            val bytes = plane.buffer
            ((bytes.get(pos).toInt() and 255) shl 16) or
                ((bytes.get(pos+1).toInt() and 255) shl 8) or (bytes.get(pos+2).toInt() and 255)
        }
        return colors.distinct().singleOrNull()
    }
}
