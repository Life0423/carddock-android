package com.life0423.carddock.core.port

/** Data-only description of one observation; Android framework objects stay in the adapter. */
data class DeviceSnapshot(
    val sourceId: String,
    val targetKey: String,
    val fields: Map<String, String>,
)

sealed interface DeviceResponse {
    data class Ready(val snapshot: DeviceSnapshot) : DeviceResponse
    data class NeedsUser(val message: String) : DeviceResponse
    data class Failed(val message: String) : DeviceResponse
}

/** A reader for this prototype. Production adapters can be added without coupling core to Android. */
interface DeviceGateway {
    suspend fun inspect(sourceId: String, targetKey: String): DeviceResponse
}
