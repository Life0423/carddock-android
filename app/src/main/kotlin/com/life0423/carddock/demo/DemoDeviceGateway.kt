package com.life0423.carddock.demo

import com.life0423.carddock.core.port.DeviceGateway
import com.life0423.carddock.core.port.DeviceResponse
import com.life0423.carddock.core.port.DeviceSnapshot
import com.life0423.carddock.core.session.DemoScenario
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Deterministic demonstration adapter. It never reads another app or makes a network request. */
class DemoDeviceGateway(private val suspendDelayMillis: Long = 1400L) : DeviceGateway {
    init {
        require(suspendDelayMillis >= 0) { "Demonstration delay cannot be negative" }
    }

    @Volatile
    private var scenario: DemoScenario = DemoScenario.NORMAL

    fun setScenario(scenario: DemoScenario) {
        this.scenario = scenario
    }

    override suspend fun inspect(sourceId: String, targetKey: String): DeviceResponse {
        currentCoroutineContext().ensureActive()
        val selectedScenario = scenario
        delay(suspendDelayMillis)
        currentCoroutineContext().ensureActive()
        val snapshot = snapshots[sourceId]
            ?: return DeviceResponse.Failed("这个来源没有演示页面，请选择内置模板。")

        return when (selectedScenario) {
            DemoScenario.NORMAL -> DeviceResponse.Ready(snapshot)
            DemoScenario.NEEDS_USER -> DeviceResponse.NeedsUser("模拟页面需要确认。完成模拟接管后，可重新核验。")
            DemoScenario.READ_FAILURE -> DeviceResponse.Failed("模拟页面暂时读取失败，上次有效结果仍保留。")
            DemoScenario.WRONG_TARGET -> DeviceResponse.Ready(snapshot.copy(targetKey = "another-demo-object"))
        }
    }

    private companion object {
        // Targets describe the observed objects, never the target requested by the caller.
        val snapshots = listOf(
            DeviceSnapshot(
                sourceId = EXPRESS_SOURCE,
                targetKey = EXPRESS_TARGET,
                fields = mapOf("pickupCode" to "A3-6281", "location" to "社区演示取件点", "status" to "待取件"),
            ),
            DeviceSnapshot(
                sourceId = DELIVERY_SOURCE,
                targetKey = DELIVERY_TARGET,
                fields = mapOf("status" to "配送中", "arrival" to "约 18 分钟", "meal" to "演示轻食套餐"),
            ),
            DeviceSnapshot(
                sourceId = TRAVEL_SOURCE,
                targetKey = TRAVEL_TARGET,
                fields = mapOf("route" to "示例南站 → 示例北站", "departure" to "16:30", "gate" to "A12"),
            ),
        ).associateBy { it.sourceId }
    }
}
