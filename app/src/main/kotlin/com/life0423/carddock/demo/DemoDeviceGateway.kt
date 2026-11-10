package com.life0423.carddock.demo

import com.life0423.carddock.core.port.DeviceGateway
import com.life0423.carddock.core.port.DeviceResponse
import com.life0423.carddock.core.port.DeviceSnapshot
import com.life0423.carddock.core.session.DemoScenario
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * Deterministic demonstration adapter. It never reads another app or makes a network request.
 * 延时前把场景抄到局部变量。等待期间切换场景不会改变这次已经开始的观察。
 * [inspect] 的 targetKey 不参与选页；错目标场景会故意换成另一个对象键，交给引擎拒绝。
 */
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
        // 挂起前抄下场景。delay 返回后不再读字段，同一次观察不会前后不一致。
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
