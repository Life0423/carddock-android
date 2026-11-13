package com.life0423.carddock.platform

import com.life0423.carddock.core.port.DeviceResponse
import com.life0423.carddock.core.port.DeviceSnapshot
import com.life0423.carddock.core.port.orderIdentity
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** 用户打开并展开详情，适配器只返回实际观察到的订单身份及白名单字段。 */
internal class MeituanGateway(
    private val connected: () -> Boolean = { CardDockAccessibilityService.instance != null },
    private val observe: () -> DetailWindow? = { CardDockAccessibilityService.instance?.observeDetail(MEITUAN_PACKAGE) },
) {
    suspend fun inspect(): DeviceResponse = withTimeoutOrNull(25_000) {
        while (true) {
            if (!connected()) return@withTimeoutOrNull DeviceResponse.NeedsUser("请先在“我的”开启无障碍读取。")
            val page = observe()
            if (page?.source == MEITUAN_PACKAGE) {
                if (page.isLogin()) return@withTimeoutOrNull DeviceResponse.NeedsUser("请先在美团登录，再重试。")
                page.readOrder()?.let { order ->
                    return@withTimeoutOrNull DeviceResponse.Ready(DeviceSnapshot(
                        MEITUAN_PACKAGE, orderIdentity(order.number),
                        mapOf("status" to order.status, "orderTime" to order.time),
                        "美团 · 当前订单详情",
                    ))
                }
            }
            delay(250)
        }
        @Suppress("UNREACHABLE_CODE")
        null
    } ?: DeviceResponse.NeedsUser("未读到完整详情。请重试，切到美团已完成订单并展开“订单信息”。")
}
