package com.life0423.carddock.platform.mika

import com.life0423.carddock.core.model.PhoneCapability

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
import com.life0423.carddock.phone.PhoneLink
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal enum class PickupListHandoff { TRIAL, OPEN }
private val readerInterrupted = DeviceResponse.NeedsUser("试运行时页面读取中断。连接恢复后，可在此页重试。", PhoneCapability.PAGE_READER)

/** 在 IO 线程启动可信虚拟屏，使用现有规则适配器读取并在结束时释放该屏。 */
internal class MikaBackgroundQuery(private val context: Context, private val focused: () -> Boolean) {
    /** 只保留最近一次查询的首页切换阶段和次数，供现场识别是否真正进入过门店菜单。 */
    @Volatile var lastMealStartup = ""
        private set

    /** 每本书独立查详情，再用同一条分享链接在新查询屏回读商品。 */
    suspend fun books(search: com.life0423.carddock.BookSearch, config: com.life0423.carddock.ai.ModelConfig?): DeviceResponse =
        com.life0423.carddock.platform.findBooks(search, config) { url, read ->
            val pkg = com.life0423.carddock.TAOBAO
            query("淘宝", { shell, surface, w, h, dpi ->
                if (url == null) shell.openIndependentLauncher(surface, w, h, dpi, pkg)
                else shell.openBookShare(surface, w, h, dpi, url)
            }, heightRatio = 2.3f, checkFilters = url == null, backgroundSource = pkg) { reader, id, connected, frame ->
                read(com.life0423.carddock.platform.BookScreen(reader, id, connected, frame::shareLink))
            }
        }

    suspend fun meal(target: com.life0423.carddock.core.rule.MealTarget, fixed: Boolean = false, openFixedDetail: Boolean = false): DeviceResponse {
        suspend fun inspect(attempt: Int): DeviceResponse {
            val prefix = if (attempt == 1) "first" else "$lastMealStartup; retry"
            lastMealStartup = "$prefix opening"
            return query("麦当劳",
                { shell, surface, w, h, dpi -> shell.openIndependentLauncher(surface,w,h,dpi,com.life0423.carddock.ai.MCD_PACKAGE) },
                mealHandoff = target.takeIf { openFixedDetail }, heightRatio = if (fixed) 3.6f else 2f, backgroundSource = com.life0423.carddock.ai.MCD_PACKAGE) { reader, id, connected, _ ->
                com.life0423.carddock.platform.readMealMenu(context, reader, target, id, fixed,
                    startup = { lastMealStartup = "$prefix $it" }, connected = connected)
            }
        }
        val first = inspect(1)
        // 首页跳转或门店定位卡住时，释放原查询屏并重读一次。明确要求选店、断连或时段缺货直接反馈。
        return if (first === com.life0423.carddock.platform.mealHomeStalled || first === com.life0423.carddock.platform.mealStorePending) inspect(2) else first
    }

    /** 探索与原卡复用配对、隔离及回收；Activity 诊断只保留工具名及观察摘要。 */
    suspend fun exploreMeal(target: com.life0423.carddock.core.rule.MealTarget, config: com.life0423.carddock.ai.ModelConfig,
        cart: Boolean = false, approve: (suspend (String) -> Boolean)? = null): DeviceResponse {
        val pkg = com.life0423.carddock.ai.MCD_PACKAGE
        lastMealStartup = "explore start"
        suspend fun attempt() = query("麦当劳", { shell, surface, w, h, dpi -> shell.openIndependentLauncher(surface, w, h, dpi, pkg) },
            heightRatio = 3.6f, backgroundSource = pkg, mealHandoff = target.takeIf { approve != null }, cartApproval = approve) { reader, id, connected, _ ->
            com.life0423.carddock.platform.exploreMenu(reader, id, target, config, cart, approve != null, connected) { event ->
                lastMealStartup += "\n" + android.os.SystemClock.elapsedRealtime() + " display=" + id + " " + event
            }
        }
        val first = attempt()
        return if (first === com.life0423.carddock.platform.mealHomeStalled) attempt() else first
    }

    suspend fun cainiao(sourceId: String, targetKey: String, handoff: PickupListHandoff? = null): DeviceResponse = pickupQuery(handoff) { reader, id, connected ->
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
        "美团最近外卖", { shell, surface, w, h, dpi -> shell.openMeituanOrders(surface, w, h, dpi) }, heightRatio = 2.5f, checkFilters = true, backgroundSource = MEITUAN_PACKAGE,
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
            val result = query("提醒来源", { shell, surface, w, h, dpi -> shell.openLauncher(surface, w, h, dpi, target) }, backgroundSource = target) {
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
        query("数量来源", { helper, surface, w, h, dpi -> helper.openLauncher(surface, w, h, dpi, source) }, backgroundSource = source) { reader, id, connected, _ ->
            readVisibleCount(source, page, subject, saved, connected,
                observe = { reader.clearCache(); reader.observeDetail(source, id, geometry = true) },
                tap = { node, cell -> connected() && reader.tapReadTarget(id, source, node, cell) }, choose = choose)
        }

    /** 每笔详情单独建立后台屏；同一查询的路线、跨页清单和截止点保存在 scan。 */
    @android.annotation.SuppressLint("NewApi")
    suspend fun monthlyOrders(source: String, scan: com.life0423.carddock.platform.OrderCollection,
        choose: ((List<String>) -> String?)? = null): DeviceResponse {
        do {
            val result = query("订单来源", { helper, surface, w, h, dpi -> helper.openIndependentLauncher(surface,w,h,dpi,source) }, heightRatio = 2.5f) {
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
        pickupQuery { reader,id,connected ->
            com.life0423.carddock.platform.inspectPickupCode(entry,
                { reader.clearCache(); reader.observe(id,pickup=true) },
                { reader.clickBackgroundLabel(id,CAINIAO_PACKAGE,it.label) },connected)
        }

    /** 旧服务中断时丢弃该屏的读数。系统短暂重绑后，从新屏重做一次前台用户发起的试查。 */
    private suspend fun pickupQuery(
        handoff: PickupListHandoff? = null,
        read: suspend (CardDockAccessibilityService, Int, () -> Boolean) -> DeviceResponse,
    ): DeviceResponse {
        var usedReader: CardDockAccessibilityService? = null
        suspend fun attempt() = query("菜鸟", { shell, surface, w, h, dpi ->
            shell.openCainiao(surface, w, h, dpi)
        }, listHandoff = handoff, backgroundSource = CAINIAO_PACKAGE.takeIf { handoff != PickupListHandoff.OPEN }) { reader, id, connected, _ ->
            usedReader = reader
            read(reader, id, connected)
        }

        val result = attempt()
        if (handoff == PickupListHandoff.OPEN || result !== readerInterrupted || !focused()) return result

        val rebound = withTimeoutOrNull(3_000) {
            CardDockAccessibilityService.connected.first { it && CardDockAccessibilityService.instance !== usedReader }
        }
        return if (rebound == true && focused()) attempt() else result
    }

    suspend fun meituan(oid: String, includeTotal: Boolean = false): DeviceResponse = query(
        "美团",
        { shell, surface, width, height, density -> shell.openMeituan(surface, width, height, density, oid) },
        heightRatio = if (includeTotal) 3.6f else 2.5f, backgroundSource = MEITUAN_PACKAGE,
    ) { reader, id, connected, _ ->
        // 此机型过滤应用日志；调试包只覆盖最近一次的脱敏阶段记录。
        val stages = if ((context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            StringBuilder("display-opened\n") else null
        MeituanGateway(
            connected = connected,
            observe = { reader.observeDetail(MEITUAN_PACKAGE, id, geometry = includeTotal) },
            trace = stages?.let { { stage -> it.appendLine(stage); Unit } },
            expand = { reader.clickBackgroundLabel(id, MEITUAN_PACKAGE, "配送骑手/支付方式等") },
            disconnected = "卡坞离开前台或页面读取断开，请回到卡坞后重试。",
            missing = "后台屏未读到完整的已完成订单详情，请检查本机路由号后重试。",
        ).inspect(includeTotal).also {
            if (stages != null) try {
                java.io.File(context.cacheDir, "meituan-stages.txt").writeText(stages.toString())
            } catch (_: java.io.IOException) {
                // 调试记录失败仍交付真实查询结果。
            }
        }
    }

    /** 移动已核对的列表任务，主屏独立复核页头与业务状态。试运行收回任务再回到预览。 */
    @androidx.annotation.RequiresApi(34)
    private suspend fun handoffList(
        shell: ICardDockDisplay, reader: CardDockAccessibilityService, id: Int,
        mode: PickupListHandoff, result: DeviceResponse.Ready,
    ): DeviceResponse {
        val expected = reader.observe(id)?.let(::classifyCainiaoPage)
        if (CardDockAccessibilityService.instance !== reader) return readerInterrupted
        if (expected !in setOf(CainiaoPage.EMPTY, CainiaoPage.PENDING) || !focused() || !shell.movePickupList(id, false))
            return DeviceResponse.NeedsUser("未能把已核验的菜鸟待取列表切到主屏，请回到卡坞重试。")
        var returned: Boolean
        val matched = try {
            kotlinx.coroutines.withTimeoutOrNull(10_000) {
                reader.clearCache()
                while (CardDockAccessibilityService.instance === reader) {
                    if (reader.observe(0)?.let(::classifyCainiaoPage) == expected) return@withTimeoutOrNull true
                    kotlinx.coroutines.delay(150)
                    reader.clearCache()
                }
                false
            } == true
        } finally {
            returned = mode != PickupListHandoff.TRIAL || shell.movePickupList(id, true)
        }
        if (!returned || mode == PickupListHandoff.TRIAL && kotlinx.coroutines.withTimeoutOrNull(5000) {
                while (!focused()) kotlinx.coroutines.delay(100)
                true
            } != true) return DeviceResponse.NeedsUser("已打开列表，请从最近任务返回卡坞后重试。")
        if (CardDockAccessibilityService.instance !== reader) return readerInterrupted
        if (!matched) return DeviceResponse.NeedsUser("主屏未能确认菜鸟待取列表，请返回卡坞重试。")
        return result
    }

    /** [read] 只拿指定屏号读页面。连接或主屏焦点丢失后，等待循环停止。 */
    private suspend fun query(
        name: String,
        open: (ICardDockDisplay, Surface, Int, Int, Int) -> Int,
        heightRatio: Float = 2f,
        checkFilters: Boolean = false,
        mealHandoff: com.life0423.carddock.core.rule.MealTarget? = null,
        cartApproval: (suspend (String) -> Boolean)? = null,
        listHandoff: PickupListHandoff? = null,
        backgroundSource: String? = null,
        read: suspend (CardDockAccessibilityService, Int, () -> Boolean, FilterFrame) -> DeviceResponse,
    ): DeviceResponse {
        if (Build.VERSION.SDK_INT < 34) return DeviceResponse.Failed("此设备不支持独立焦点的后台查询。")
        if (backgroundSource == null && !focused()) return DeviceResponse.NeedsUser("请回到卡坞再查询。")
        val reader = CardDockAccessibilityService.instance
            ?: return DeviceResponse.NeedsUser("请在系统无障碍设置开启「${context.getString(com.life0423.carddock.R.string.accessibility_label)}」，再试运行。", PhoneCapability.PAGE_READER)
        // 进程退出会同时释放助手。只在前台查询需要它时复用本机已确认的配对。
        if (DockMikaHandoff.display.value == null && PhoneLink.prefs(context).getBoolean("paired", false)) {
            if (!PhoneLink.connect(context, null)) {
                val reason = if (PhoneLink.busy.value) "本机连接操作进行中，请稍后重试。"
                    else PhoneLink.message.value.ifBlank { "后台助手未连接，请在手机运行环境重试。" }
                return DeviceResponse.NeedsUser(reason, PhoneCapability.HELPER)
            }
            if (backgroundSource == null && !focused()) return DeviceResponse.NeedsUser("请回到卡坞再查询。")
        }
        val shell = DockMikaHandoff.display.value
            ?: return DeviceResponse.NeedsUser("后台助手未连接，请在手机运行环境连接后重试。", PhoneCapability.HELPER)
        fun isolated() = if (backgroundSource == null) focused() else !reader.primaryUses(backgroundSource)
        if (!isolated()) return DeviceResponse.NeedsUser("$name 正在主屏使用，请稍后重试。")
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels.coerceAtMost(1080)
        // 普通详情同时核对状态、号码和时间；金额查询还需同一帧的费用区。
        val height = (width * heightRatio).toInt()
        val sink = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, if (checkFilters) 3 else 2)
        val frames = HandlerThread("carddock-display").apply { start() }
        val filterFrame = FilterFrame(sink.surface, Handler(frames.looper))
        try {
            sink.setOnImageAvailableListener({ it.acquireLatestImage()?.let { image -> if (checkFilters) filterFrame.replace(image) else image.close() } }, Handler(frames.looper))
            val id = open(shell, sink.surface, width, height, metrics.densityDpi * width / metrics.widthPixels)
            if (id == -2) return DeviceResponse.NeedsUser("${name}正在主屏使用，请切到其他应用后重试。")
            if (id <= 0) return DeviceResponse.Failed("${name}未能在后台屏打开。")
            try {
                val response = read(reader, id, {
                    isolated() && CardDockAccessibilityService.instance === reader && shell.asBinder().isBinderAlive
                }, filterFrame)
                // 只有用户主动打开且刚完成固定组合复核，才交出原任务；切走卡坞时不抢回主屏。
                if (mealHandoff != null && response is DeviceResponse.Ready) {
                    val price = response.snapshot.fields["price"]
                    if (cartApproval != null) {
                        if (!focused() || !cartApproval(requireNotNull(price))) return DeviceResponse.NeedsUser("本次加购已取消。")
                        withTimeoutOrNull(1500) { while (!focused()) kotlinx.coroutines.delay(50) }
                    }
                    if (!focused() || CardDockAccessibilityService.instance !== reader || reader.primaryUses(com.life0423.carddock.ai.MCD_PACKAGE))
                        return DeviceResponse.NeedsUser("请回到卡坞，再点商品操作。")
                    if (!shell.showMealDetail(id)) return DeviceResponse.NeedsUser("商品已核对，但未能打开该商品页。请回到卡坞重试。")
                    if (cartApproval != null) return com.life0423.carddock.platform.addConfirmedMeal(reader, mealHandoff,
                        requireNotNull(price), response.snapshot.fields.getValue("before").toInt(), response.snapshot.fields.getValue("matchingBefore").toInt()) { lastMealStartup += "\n$it" }
                    val matched = withTimeoutOrNull(7_000) {
                        while (CardDockAccessibilityService.instance === reader) {
                            reader.clearCache()
                            val page = reader.observeDetail(com.life0423.carddock.ai.MCD_PACKAGE, 0)?.let { com.life0423.carddock.platform.MealPage(it) }
                            if (page?.product == mealHandoff.product && page.detailOffersProduct) return@withTimeoutOrNull true
                            kotlinx.coroutines.delay(150)
                        }
                        false
                    }
                    return if (matched == true) response else DeviceResponse.NeedsUser("切换后未确认目标商品页，请返回卡坞重试。")
                }
                if (listHandoff == PickupListHandoff.OPEN && response is DeviceResponse.Ready) {
                    if (!focused() || CardDockAccessibilityService.instance !== reader) return DeviceResponse.NeedsUser("请回到卡坞后点开官方列表。")
                    return handoffList(shell, reader, id, listHandoff, response)
                }
                return when {
                    !shell.asBinder().isBinderAlive -> DeviceResponse.NeedsUser("后台助手已断开，请连接后重试。", PhoneCapability.HELPER)
                    CardDockAccessibilityService.instance !== reader -> readerInterrupted
                    !isolated() -> DeviceResponse.NeedsUser(if (backgroundSource == null) "请回到卡坞后重试。" else "${name}正在主屏使用。请切到其他应用后重试，上次结果已保留。")
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
            return DeviceResponse.NeedsUser("后台助手已断开，请连接后重试。", PhoneCapability.HELPER)
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

    /** 商品分享层下半屏的二维码仅在本机解码，避免剪贴板或账号历史参与取证。 */
    @Synchronized fun shareLink(): String? {
        val image = latest ?: return null
        val plane = image.planes.first()
        val width = image.width
        val height = image.height / 2
        val bytes = ByteArray(width * height)
        val pixels = plane.buffer
        for (y in 0 until height) {
            var offset = (y + height) * plane.rowStride
            for (x in 0 until width) {
                bytes[y * width + x] = pixels.get(offset)
                offset += plane.pixelStride
            }
        }
        val source = com.google.zxing.PlanarYUVLuminanceSource(bytes, width, height, 0, 0, width, height, false)
        return try {
            com.google.zxing.qrcode.QRCodeReader().decode(com.google.zxing.BinaryBitmap(
                com.google.zxing.common.HybridBinarizer(source))).text.takeIf(com.life0423.carddock.bookShareUrl::matches)
        } catch (_: com.google.zxing.ReaderException) { null }
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
