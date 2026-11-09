package com.life0423.carddock.demo

import com.life0423.carddock.core.engine.EngineResult
import com.life0423.carddock.core.engine.RuleEngine
import com.life0423.carddock.core.port.DeviceResponse
import com.life0423.carddock.core.session.DemoScenario
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DemoDeviceGatewayTest {
    @Test
    fun aRequestedTargetCannotChangeTheObservedTarget() = runTest {
        val rule = TemplateCatalog.templates.first().rule
        val response = DemoDeviceGateway(suspendDelayMillis = 0L).inspect(rule.sourceId, "incorrect-target")

        assertTrue(response is DeviceResponse.Ready)
        assertEquals(rule.targetKey, (response as DeviceResponse.Ready).snapshot.targetKey)
    }

    @Test
    fun scenariosProduceTheExpectedEngineOutcomes() = runTest {
        val device = DemoDeviceGateway(suspendDelayMillis = 0L)
        val engine = RuleEngine(device)
        val rule = TemplateCatalog.templates.first().rule

        device.setScenario(DemoScenario.NEEDS_USER)
        assertTrue(engine.execute(rule) is EngineResult.NeedsUser)
        device.setScenario(DemoScenario.READ_FAILURE)
        assertEquals("DEVICE_READ_FAILED", (engine.execute(rule) as EngineResult.Failure).code)
        device.setScenario(DemoScenario.WRONG_TARGET)
        assertEquals("TARGET_MISMATCH", (engine.execute(rule) as EngineResult.Failure).code)
    }

    @Test
    fun simulatedDeviceDelayIsCancellable() = runTest {
        val rule = TemplateCatalog.templates.first().rule
        val pending = async { DemoDeviceGateway().inspect(rule.sourceId, rule.targetKey) }
        runCurrent()

        pending.cancel()
        try {
            pending.await()
            fail("Device cancellation must propagate")
        } catch (_: CancellationException) {
            // Expected: no fake Ready result after cancellation.
        }
        assertTrue(pending.isCancelled)
    }
}
