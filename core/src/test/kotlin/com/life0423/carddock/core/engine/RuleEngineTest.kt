package com.life0423.carddock.core.engine

import com.life0423.carddock.core.model.FieldValue
import com.life0423.carddock.core.port.DeviceGateway
import com.life0423.carddock.core.port.DeviceResponse
import com.life0423.carddock.core.port.DeviceSnapshot
import com.life0423.carddock.core.rule.RuleSpec
import com.life0423.carddock.core.rule.RuleStep
import com.life0423.carddock.core.rule.RuleValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RuleEngineTest {
    @Test
    fun validRuleReturnsVerifiedFieldsAndCompletionTime() = runBlocking {
        val device = FakeDevice()
        val progress = mutableListOf<RunProgress>()

        val result = RuleEngine(device, now = { 1234L }).execute(validRule()) { progress += it }

        assertTrue(result is EngineResult.Success)
        val snapshot = (result as EngineResult.Success).snapshot
        assertEquals(listOf(FieldValue("code", "取件码", "A3-6281")), snapshot.fields)
        assertEquals(1234L, snapshot.fetchedAt)
        assertEquals("模拟设备 · 演示数据", snapshot.sourceLabel)
        assertEquals(listOf(0, 1, 2, 3), progress.map { it.currentStep })
        assertTrue(progress.all { it.totalSteps == 3 })
        assertEquals(listOf("demo.express" to "parcel-demo-001"), device.requests)
    }

    @Test
    fun invalidPlansAreRejectedBeforeAnyDeviceAccess() = runBlocking {
        val good = validRule()
        val invalidPlans = listOf(
            "unknown schema" to good.copy(schemaVersion = 99),
            "blank rule id" to good.copy(id = " "),
            "blank source" to good.copy(sourceId = ""),
            "blank target" to good.copy(targetKey = ""),
            "empty plan" to good.copy(steps = emptyList()),
            "too many steps" to good.copy(
                steps = List(RuleValidator.MAX_STEPS + 1) { RuleStep.AssertTarget(good.targetKey) },
            ),
            "read before assertion" to good.copy(steps = good.steps.drop(1)),
            "inconsistent assertion" to good.copy(
                steps = listOf(RuleStep.AssertTarget("another-parcel")) + good.steps.drop(1),
            ),
            "duplicate output" to good.copy(steps = good.steps + good.steps[1]),
            "unknown required reference" to good.copy(steps = good.steps + RuleStep.RequireField("missing")),
            "required reference before read" to good.copy(
                steps = listOf(good.steps[0], good.steps[2], good.steps[1]),
            ),
            "blank source field" to good.copy(
                steps = listOf(good.steps[0], RuleStep.ReadField(" ", "code", "取件码")),
            ),
            "blank output field" to good.copy(
                steps = listOf(good.steps[0], RuleStep.ReadField("pickupCode", "", "取件码")),
            ),
            "blank field label" to good.copy(
                steps = listOf(good.steps[0], RuleStep.ReadField("pickupCode", "code", " ")),
            ),
            "assertion without reads" to good.copy(steps = listOf(good.steps[0])),
        )

        for ((name, plan) in invalidPlans) {
            val device = FakeDevice()
            val result = RuleEngine(device).execute(plan)
            assertTrue(name, result is EngineResult.Failure)
            assertEquals(name, 0, device.requests.size)
            val expectedCode = if (name == "unknown schema") "UNSUPPORTED_SCHEMA" else "INVALID_RULE"
            assertEquals(name, expectedCode, (result as EngineResult.Failure).code)
        }
    }

    @Test
    fun wrongSourceFailsBeforeExtractingFields() = runBlocking {
        val device = FakeDevice(readySnapshot().copy(sourceId = "demo.other"))
        val progress = mutableListOf<RunProgress>()

        val result = RuleEngine(device).execute(validRule()) { progress += it }

        assertFailure("SOURCE_MISMATCH", result)
        assertEquals(listOf(0), progress.map { it.currentStep })
    }

    @Test
    fun wrongTargetFailsEvenWhenItsFieldsLookValid() = runBlocking {
        val device = FakeDevice(readySnapshot().copy(targetKey = "another-parcel"))

        assertFailure("TARGET_MISMATCH", RuleEngine(device).execute(validRule()))
    }

    @Test
    fun missingFieldFailsWithoutClaimingTheReadStepSucceeded() = runBlocking {
        val device = FakeDevice(readySnapshot().copy(fields = emptyMap()))
        val progress = mutableListOf<RunProgress>()

        val result = RuleEngine(device).execute(validRule()) { progress += it }

        assertFailure("FIELD_NOT_FOUND", result)
        assertEquals(listOf(0, 1), progress.map { it.currentStep })
    }

    @Test
    fun blankRequiredValueStopsRemainingSteps() = runBlocking {
        val device = FakeDevice(readySnapshot().copy(fields = mapOf("pickupCode" to " \n\t ", "location" to "演示取件点")))
        val rule = validRule().let {
            it.copy(steps = it.steps + RuleStep.ReadField("location", "location", "取件位置"))
        }
        val progress = mutableListOf<RunProgress>()

        val result = RuleEngine(device).execute(rule) { progress += it }

        assertFailure("REQUIRED_FIELD_EMPTY", result)
        assertEquals(listOf(0, 1, 2), progress.map { it.currentStep })
    }

    @Test
    fun reportedDeviceFailurePreservesItsUserFacingReason() = runBlocking {
        val device = respondingWith(DeviceResponse.Failed("演示读取失败"))

        val result = RuleEngine(device).execute(validRule())

        assertFailure("DEVICE_READ_FAILED", result)
        assertEquals("演示读取失败", (result as EngineResult.Failure).message)
    }

    @Test
    fun requiredUserInterventionIsNotReportedAsSuccessOrOrdinaryFailure() = runBlocking {
        val device = respondingWith(DeviceResponse.NeedsUser("请先完成演示接管"))
        var clockCalled = false

        val result = RuleEngine(device, now = { clockCalled = true; 10L }).execute(validRule())

        assertEquals(EngineResult.NeedsUser("请先完成演示接管"), result)
        assertFalse(clockCalled)
    }

    @Test
    fun unexpectedDeviceExceptionBecomesASanitizedFailure() = runBlocking {
        val device = object : DeviceGateway {
            override suspend fun inspect(sourceId: String, targetKey: String): DeviceResponse {
                error("sensitive internal device details")
            }
        }

        val result = RuleEngine(device).execute(validRule())

        assertFailure("DEVICE_ERROR", result)
        assertFalse((result as EngineResult.Failure).message.contains("sensitive"))
    }

    @Test
    fun cancellingASuspendedDeviceReadPropagatesCancellation() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val device = object : DeviceGateway {
            override suspend fun inspect(sourceId: String, targetKey: String): DeviceResponse {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val progress = mutableListOf<RunProgress>()
        val running = async { RuleEngine(device).execute(validRule()) { progress += it } }
        entered.await()

        running.cancel()
        try {
            running.await()
            fail("Cancellation must propagate rather than returning an EngineResult")
        } catch (_: CancellationException) {
            // Expected: session ownership remains with the caller.
        }

        assertTrue(running.isCancelled)
        assertEquals(listOf(0), progress.map { it.currentStep })
    }

    @Test
    fun cancellationFromProgressDoesNotRunLaterSteps() {
        val progress = mutableListOf<Int>()

        assertThrows(CancellationException::class.java) {
            runBlocking {
                RuleEngine(FakeDevice()).execute(validRule()) {
                    progress += it.currentStep
                    if (it.currentStep == 1) throw CancellationException("user cancelled")
                }
            }
        }

        assertEquals(listOf(0, 1), progress)
    }

    @Test
    fun callerCannotMutateTheValidatedPlanDuringDeviceAccess() = runBlocking {
        val steps = validRule().steps.toMutableList()
        val rule = validRule().copy(steps = steps)
        val device = object : DeviceGateway {
            override suspend fun inspect(sourceId: String, targetKey: String): DeviceResponse {
                steps.clear()
                return DeviceResponse.Ready(readySnapshot())
            }
        }

        val result = RuleEngine(device).execute(rule)

        assertTrue(result is EngineResult.Success)
        assertEquals("A3-6281", (result as EngineResult.Success).snapshot.fields.single().value)
    }

    private fun assertFailure(code: String, result: EngineResult) {
        assertTrue("Expected $code, got $result", result is EngineResult.Failure)
        assertEquals(code, (result as EngineResult.Failure).code)
    }

    private fun validRule() = RuleSpec(
        id = "express-pickup",
        sourceId = "demo.express",
        targetKey = "parcel-demo-001",
        steps = listOf(
            RuleStep.AssertTarget("parcel-demo-001"),
            RuleStep.ReadField("pickupCode", "code", "取件码"),
            RuleStep.RequireField("code"),
        ),
    )

    private fun readySnapshot() = DeviceSnapshot(
        sourceId = "demo.express",
        targetKey = "parcel-demo-001",
        fields = mapOf("pickupCode" to " A3-6281 "),
    )

    private fun respondingWith(response: DeviceResponse) = object : DeviceGateway {
        override suspend fun inspect(sourceId: String, targetKey: String) = response
    }

    private inner class FakeDevice(private val snapshot: DeviceSnapshot = readySnapshot()) : DeviceGateway {
        val requests = mutableListOf<Pair<String, String>>()

        override suspend fun inspect(sourceId: String, targetKey: String): DeviceResponse {
            requests += sourceId to targetKey
            return DeviceResponse.Ready(snapshot)
        }
    }
}
