package com.life0423.carddock.platform

import com.life0423.carddock.core.port.DeviceResponse
import com.life0423.carddock.core.port.DeviceSnapshot
import com.life0423.carddock.core.port.orderIdentity
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** 读取美团详情，后台模式可展开订单信息；诊断只报告白名单锚点的出现情况。 */
internal class MeituanGateway(
    private val connected: () -> Boolean = { CardDockAccessibilityService.instance != null },
    private val observe: () -> DetailWindow? = { CardDockAccessibilityService.instance?.observeDetail(MEITUAN_PACKAGE) },
    private val expand: () -> Boolean = { false },
    private val disconnected: String = "请先在“我的”开启无障碍读取。",
    private val trace: ((String) -> Unit)? = null,
    private val missing: String = "未读到完整详情。请重试，切到美团已完成订单并展开“订单信息”。",
) {
    suspend fun inspect(): DeviceResponse = withTimeoutOrNull(25_000) {
        var expansionAttempts = 0
        var lastStage = ""
        val start = System.nanoTime()
        fun stage(value: String) {
            if (value != lastStage) trace?.invoke("$value elapsedMs=${(System.nanoTime() - start) / 1_000_000}")
            lastStage = value
        }
        while (true) {
            if (!connected()) {
                stage("disconnected")
                return@withTimeoutOrNull DeviceResponse.NeedsUser(disconnected)
            }
            val page = observe()
            if (trace != null) {
                val visible = page?.nodes.orEmpty().filter { it.visible }
                val labels = visible.map { it.label }
                val ids = page?.nodes.orEmpty().map { it.id?.substringAfterLast('/') }
                stage(if (page == null) "waiting-window" else
                    "detail=${ids.any { it in setOf("ii7", "ifs", "q04") }} header=${ids.any { it in setOf("sur", "r19", "c9_") }} " +
                    "complete=${"订单已完成" in labels} info=${"订单信息" in labels} " +
                    "toggle=${"配送骑手/支付方式等" in labels} number=${"订单号码" in labels} time=${"下单时间" in labels} attempts=$expansionAttempts")
            }
            if (page?.source == MEITUAN_PACKAGE) {
                if (page.isLogin()) return@withTimeoutOrNull DeviceResponse.NeedsUser("请先在美团登录，再重试。")
                page.readOrder()?.let { order ->
                    stage("verified")
                    return@withTimeoutOrNull DeviceResponse.Ready(DeviceSnapshot(
                        MEITUAN_PACKAGE, orderIdentity(order.number),
                        mapOf("status" to order.status, "orderTime" to order.time),
                        "美团 · 当前订单详情",
                    ))
                }
                // vivo 实测在详情刚挂载时 ACTION_CLICK 会返回成功但行仍折叠。
                // 每次都重新核对折叠行，给页面一秒更新，再决定是否重新点击。
                if (expansionAttempts < 3 && page.hasCollapsedMeituanOrder() && expand()) {
                    expansionAttempts++
                    delay(1_000)
                }
            }
            delay(250)
        }
        @Suppress("UNREACHABLE_CODE")
        null
    } ?: DeviceResponse.NeedsUser(missing)
}
