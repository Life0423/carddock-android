package com.life0423.carddock.platform

import com.life0423.carddock.core.model.PickupEntry
import com.life0423.carddock.core.port.DeviceResponse
import com.life0423.carddock.core.port.DeviceSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** 学习和重放都只接触本机的导航标记。收到官方码页的标题及刷新提示就结束观察。 */
internal suspend fun inspectPickupCode(
    entry: PickupEntry?, observe: () -> CainiaoObservation?, tap: (PickupEntry) -> Boolean,
    connected: () -> Boolean, acceptAlreadyOpen: Boolean = false,
): DeviceResponse {
    var opened = false
    return withTimeoutOrNull(18_000) {
        while (connected()) {
            delay(300)
            val page = observe() ?: continue
            if (page.packageName != CAINIAO_PACKAGE) return@withTimeoutOrNull DeviceResponse.NeedsUser("已离开菜鸟，本次亮码停止。")
            val tags = page.labels
            if (page.password || classifyCainiaoPage(page) == CainiaoPage.AUTH || "待处理" in tags)
                return@withTimeoutOrNull DeviceResponse.NeedsUser("请在菜鸟完成授权、登录或刷新身份码，再回卡坞重试。")
            val home = "取包裹" in tags && "我的" in tags
            if ((opened || acceptAlreadyOpen) && !home && "身份码" in tags && "身份码刷新提示" in tags)
                return@withTimeoutOrNull DeviceResponse.Ready(DeviceSnapshot(CAINIAO_PACKAGE,"pickup-code",emptyMap(),"菜鸟官方身份码页"))
            if (opened || !home) continue
            val entries = PickupEntry.entries.filter { it.label in tags }
            if (entry == null && entries.isNotEmpty()) return@withTimeoutOrNull DeviceResponse.Ready(
                DeviceSnapshot(CAINIAO_PACKAGE,"pickup-entries",entries.associate { it.name to it.label }))
            if (entry != null && entry in entries && tap(entry)) opened = true
        }
        DeviceResponse.NeedsUser("页面读取已断开，请重新试运行。")
    } ?: DeviceResponse.NeedsUser(if (opened) "已打开菜鸟入口，尚未核验到可刷新的官方身份码。" else "未找到菜鸟首页上已核验的亮码入口。")
}
