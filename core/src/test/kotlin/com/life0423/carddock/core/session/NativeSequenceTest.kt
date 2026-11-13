package com.life0423.carddock.core.session

import com.life0423.carddock.core.engine.RuleEngine
import com.life0423.carddock.core.model.AppPrice
import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.model.RunPhase
import com.life0423.carddock.core.model.TemplateDefinition
import com.life0423.carddock.core.model.TemplateKind
import com.life0423.carddock.core.port.CompileResult
import com.life0423.carddock.core.port.RuleCompiler
import com.life0423.carddock.core.port.DeviceGateway
import com.life0423.carddock.core.port.DeviceResponse
import com.life0423.carddock.core.port.DeviceSnapshot
import com.life0423.carddock.core.port.ReadOptions
import com.life0423.carddock.core.rule.RuleSpec
import com.life0423.carddock.core.rule.RuleStep
import com.life0423.carddock.core.testing.FixedCompiler
import com.life0423.carddock.core.testing.MemoryCardStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** 顺序核验、展示价独立保存以及模型候选刷新走同一个生产控制器。 */
@OptIn(ExperimentalCoroutinesApi::class)
class NativeSequenceTest {
    @Test fun sequentialReadsWaitForUserAndPreservePriceAcrossPublicRefresh() = runTest {
        val price = AppPrice("10", "CNY", "规格", "到手价", null, 23)
        fun rule(key: String) = RuleSpec(id = key, sourceId = key, targetKey = key, steps = listOf(
            RuleStep.AssertTarget(key), RuleStep.ReadField("status", "status", "状态"),
        ))
        val manual = CardRecord("manual", "手动", TemplateKind.DELIVERY, rule("manual"))
        val jd = CardRecord("jd", "京东", TemplateKind.SHOPPING, rule("jd"), appPrice = price)
        val store = MemoryCardStore(listOf(manual, jd))
        val calls = mutableListOf<String>()
        val gateway = object : DeviceGateway {
            override suspend fun inspect(sourceId: String, targetKey: String) = inspect(sourceId, targetKey, ReadOptions())
            override suspend fun inspect(sourceId: String, targetKey: String, options: ReadOptions): DeviceResponse {
                calls += sourceId
                delay(100)
                return if (sourceId == "manual") DeviceResponse.NeedsUser("请重试")
                else if (options.appPrice) DeviceResponse.Failed("错商品")
                else DeviceResponse.Ready(DeviceSnapshot(sourceId, targetKey, mapOf("status" to "ok")))
            }
        }
        val template = TemplateDefinition("t", "模板", "", TemplateKind.SHOPPING, "京东", jd.rule)
        val controller = PrototypeController(store, FixedCompiler(template), RuleEngine(gateway),
            listOf(template), backgroundScope, needsManualSequenceStart = { it.id == manual.id })
        controller.initialize()
        runCurrent()
        val actions = controller.actions
        actions.refreshSequence(listOf(manual.id, jd.id))
        runCurrent()
        assertTrue(controller.state.value.sequence!!.awaitingStart)
        assertTrue(calls.isEmpty())
        actions.advanceSequence()
        testScheduler.advanceTimeBy(300)
        runCurrent()
        assertEquals(listOf("manual", "jd"), calls)
        assertEquals(RunPhase.NEEDS_USER, store.disk!!.first().run!!.phase)
        val verified = store.disk!!.last()
        assertEquals(RunPhase.SUCCEEDED, verified.run!!.phase)
        assertEquals(price, verified.appPrice)
        actions.verifyPrice(jd.id)
        testScheduler.advanceTimeBy(200)
        runCurrent()
        assertEquals(RunPhase.FAILED, store.disk!!.last().run!!.phase)
        assertEquals(verified.snapshot, store.disk!!.last().snapshot)
        assertEquals(price, store.disk!!.last().appPrice)
        actions.refreshCard(jd.id)
        testScheduler.advanceTimeBy(200)
        runCurrent()
        assertEquals(RunPhase.SUCCEEDED, store.disk!!.last().run!!.phase)
        assertEquals(price, store.disk!!.last().appPrice)
        assertTrue(store.disk!!.last().priceVerificationFailed)
    }

    @Test fun modelCandidateNeedsTrialAndLaterRefreshDoesNotCompileAgain() = runTest {
        val rule = RuleSpec(id = "model", sourceId = "source", targetKey = "target", steps = listOf(
            RuleStep.AssertTarget("target"), RuleStep.ReadField("status", "status", "状态"),
        ))
        val template = TemplateDefinition("t", "菜鸟", "", TemplateKind.EXPRESS, "菜鸟", rule)
        var requests = 0
        val store = MemoryCardStore(emptyList())
        val controller = PrototypeController(store, FixedCompiler(template),
            RuleEngine(object : DeviceGateway {
                override suspend fun inspect(sourceId: String, targetKey: String): DeviceResponse =
                    DeviceResponse.Ready(DeviceSnapshot(sourceId, targetKey, mapOf("status" to "空")))
            }), listOf(template), backgroundScope, modelCompiler = object : RuleCompiler {
                override suspend fun compile(prompt: String): CompileResult {
                    requests++
                    return CompileResult.Success(template)
                }
            })
        controller.initialize()
        controller.openModelCreator(template)
        controller.actions.trialDraft()
        runCurrent()
        assertNull(controller.state.value.draft?.preview)
        controller.actions.generateCandidate()
        runCurrent()
        controller.actions.saveDraft()
        runCurrent()
        assertTrue(store.disk!!.isEmpty())
        controller.actions.trialDraft()
        runCurrent()
        controller.actions.saveDraft()
        runCurrent()
        controller.actions.refreshCard(store.disk!!.single().id)
        runCurrent()
        assertEquals(1, requests)
        assertEquals(RunPhase.SUCCEEDED, store.disk!!.single().run!!.phase)
    }
}
