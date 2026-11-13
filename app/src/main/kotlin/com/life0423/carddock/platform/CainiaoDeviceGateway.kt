package com.life0423.carddock.platform

import com.life0423.carddock.core.port.DeviceGateway
import com.life0423.carddock.core.port.DeviceResponse
import com.life0423.carddock.core.port.DeviceSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

internal const val CAINIAO_PACKAGE = "com.cainiao.wireless"
internal const val CAINIAO_EMPTY_TARGET = "cainiao.pickup.empty"

/** 一次窗口观察，只含当前可见标签；不保留 Android 节点或账号信息。 */
internal data class CainiaoObservation(val packageName: String?, val labels: Set<String>, val password: Boolean)
internal enum class CainiaoPage { OTHER, AUTH, EMPTY, UNKNOWN }

/** 移植自 experiments/mika-android 的 CainiaoEvidence：明确标题和空状态缺一不可。 */
internal fun classifyCainiaoPage(page: CainiaoObservation): CainiaoPage {
    if (page.packageName != CAINIAO_PACKAGE) return CainiaoPage.OTHER
    val labels = page.labels
    if (page.password || "手机号登录" in labels || "登录/注册" in labels ||
        ("登录" in labels && labels.any { it.contains("手机号") || it.contains("验证码") })) return CainiaoPage.AUTH
    return if ("取包裹" in labels && "暂无待取包裹" in labels) CainiaoPage.EMPTY else CainiaoPage.UNKNOWN
}

/** 用户主动读取后等待手动切页；不启动应用、不点击、不读取历史缓存。 */
class CainiaoDeviceGateway internal constructor(
    private val connected: () -> Boolean,
    private val observe: () -> CainiaoObservation?,
) : DeviceGateway {
    constructor() : this(
        { CardDockAccessibilityService.instance != null },
        { CardDockAccessibilityService.instance?.observe() },
    )

    override suspend fun inspect(sourceId: String, targetKey: String): DeviceResponse {
        if (sourceId != CAINIAO_PACKAGE || targetKey != CAINIAO_EMPTY_TARGET) {
            return DeviceResponse.Failed("当前仅支持菜鸟明确的待取空状态。")
        }
        return withTimeoutOrNull(20_000) {
            var result: DeviceResponse? = null
            while (result == null) {
                if (!connected()) return@withTimeoutOrNull DeviceResponse.NeedsUser("请在“我的”中开启卡坞无障碍读取，再重试。")
                when (observe()?.let(::classifyCainiaoPage)) {
                    CainiaoPage.AUTH -> result = DeviceResponse.NeedsUser("请先在菜鸟完成登录，再重新读取。")
                    CainiaoPage.EMPTY -> result = DeviceResponse.Ready(DeviceSnapshot(
                        sourceId = CAINIAO_PACKAGE,
                        targetKey = CAINIAO_EMPTY_TARGET,
                        fields = mapOf("status" to "暂无待取包裹"),
                        sourceLabel = "菜鸟 · 当前页面",
                    ))
                    else -> delay(250)
                }
            }
            result
        } ?: DeviceResponse.NeedsUser("未核验到空状态。请重新读取，并在 20 秒内切到菜鸟“取包裹”页；有包裹时暂不支持提取。")
    }
}
