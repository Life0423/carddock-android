package com.life0423.carddock.core.port

/**
 * Data-only description of one observation; Android framework objects stay in the adapter.
 * targetKey 是这次实际观察到的对象，不是调用方希望的目标。两者不一致时由引擎拒绝，网关不负责改写成请求值。
 */
data class DeviceSnapshot(
    val sourceId: String,
    val targetKey: String,
    val fields: Map<String, String>,
    val sourceLabel: String = "模拟设备 · 演示数据",
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
    suspend fun inspect(sourceId: String, targetKey: String): DeviceResponse
}
