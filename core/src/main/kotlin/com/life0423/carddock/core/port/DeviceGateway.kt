package com.life0423.carddock.core.port

import com.life0423.carddock.core.model.AppPrice
import com.life0423.carddock.core.model.LocalTarget
import java.security.MessageDigest

/** 一次读取的本机目标和模式。默认仍读取规则定义的字段。 */
data class ReadOptions(val localTarget: LocalTarget? = null, val appPrice: Boolean = false, val requestedAt: Long? = null)

/** 观测号码和配置号码各自生成键，供引擎比对且避免号码进入快照。 */
fun orderIdentity(number: String): String =
    MessageDigest.getInstance("SHA-256").digest(number.toByteArray()).joinToString("") { "%02x".format(it) }


/**
 * Data-only description of one observation; Android framework objects stay in the adapter.
 * targetKey 是这次实际观察到的对象，不是调用方希望的目标。两者不一致时由引擎拒绝，网关不负责改写成请求值。
 */
data class DeviceSnapshot(
    val sourceId: String,
    val targetKey: String,
    val fields: Map<String, String>,
    val sourceLabel: String = "模拟设备 · 演示数据",
    val appPrice: AppPrice? = null,
)

/** 一次观察的三种结果。Ready 仍要经过引擎的来源和目标核对，不能直接写成卡片快照。 */
sealed interface DeviceResponse {
    data class Ready(val snapshot: DeviceSnapshot) : DeviceResponse
    data class NeedsUser(val message: String) : DeviceResponse
    data class Failed(val message: String) : DeviceResponse
}

/**
 * A reader for this prototype. Production adapters can be added without coupling core to Android.
 * 只读。取消必须抛出 CancellationException，不能收成 Failed，否则会话会把取消记成读取失败。
 */
interface DeviceGateway {
    fun instruction(sourceId: String, options: ReadOptions): String = "正在读取页面"
    suspend fun inspect(sourceId: String, targetKey: String): DeviceResponse
    suspend fun inspect(sourceId: String, targetKey: String, options: ReadOptions): DeviceResponse =
        inspect(sourceId, targetKey)
}
