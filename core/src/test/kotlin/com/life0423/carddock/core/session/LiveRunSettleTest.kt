package com.life0423.carddock.core.session

import com.life0423.carddock.core.engine.RuleEngine
import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.model.RunInfo
import com.life0423.carddock.core.model.RunPhase
import com.life0423.carddock.core.model.TemplateDefinition
import com.life0423.carddock.core.model.TemplateKind
import com.life0423.carddock.core.port.CardStore
import com.life0423.carddock.core.port.CompileResult
import com.life0423.carddock.core.port.DeviceGateway
import com.life0423.carddock.core.port.DeviceResponse
import com.life0423.carddock.core.port.DeviceSnapshot
import com.life0423.carddock.core.port.RuleCompiler
import com.life0423.carddock.core.rule.RuleSpec
import com.life0423.carddock.core.rule.RuleStep
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 另一张卡保存失败时，磁盘上没有活协程的忙碌卡仍要收成已中断，
 * 但正在设备读取里的任务必须保持 RUNNING，直到它自己结束。
 * 门闩停住设备读取，调度器排空即可，不靠延时。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveRunSettleTest {
    private val rule = RuleSpec(
        id = "r",
        sourceId = "source",
        targetKey = "fixed",
        steps = listOf(
            RuleStep.AssertTarget("fixed"),
            RuleStep.ReadField("status", "status", "状态"),
        ),
    )

    @Test
    fun failedSaveOfAnotherCardDoesNotInterruptALiveRunningJob() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var failSaves = false
        var disk = listOf(card("a"), card("b"), card("c"))
        val store = object : CardStore {
            override suspend fun load(): List<CardRecord> = disk

            override suspend fun save(cards: List<CardRecord>) {
                if (failSaves) throw IOException("other card save failed")
                disk = cards
            }
        }
        val template = TemplateDefinition("t", "模板", "演示", TemplateKind.EXPRESS, "快递", rule)
        val controller = PrototypeController(
            store,
            object : RuleCompiler {
                override suspend fun compile(prompt: String) = CompileResult.Success(template)
            },
            RuleEngine(object : DeviceGateway {
                override suspend fun inspect(sourceId: String, targetKey: String): DeviceResponse {
                    entered.complete(Unit)
                    release.await()
                    currentCoroutineContext().ensureActive()
                    return DeviceResponse.Ready(
                        DeviceSnapshot(sourceId, targetKey, mapOf("status" to "ok")),
                    )
                }
            }),
            listOf(template),
            backgroundScope,
            now = { 50L },
            newId = { "live-run" },
        )
        controller.initialize()
        runCurrent()
        controller.actions.refreshCard("a")
        runCurrent()
        assertTrue("设备读取应已停在门闩上", entered.isCompleted)
        assertEquals(RunPhase.RUNNING, phase(controller, "a"))

        // 这次失败的保存不会写上。C 在盘上仍是没有协程的 RUNNING，A 的协程还停在读取里。
        disk = disk.map { current ->
            if (current.id == "c") {
                current.copy(run = RunInfo("crash-residue", RunPhase.RUNNING, startedAt = 1))
            } else {
                current
            }
        }
        failSaves = true
        controller.actions.deleteCard("b")
        runCurrent()

        assertEquals(RunPhase.RUNNING, phase(controller, "a"))
        assertEquals("live-run", controller.state.value.cards.first { it.id == "a" }.run!!.id)
        assertEquals(RunPhase.INTERRUPTED, phase(controller, "c"))
        assertTrue(controller.state.value.cards.any { it.id == "b" })

        failSaves = false
        release.complete(Unit)
        runCurrent()
        assertEquals(RunPhase.SUCCEEDED, phase(controller, "a"))
    }

    private fun card(id: String) = CardRecord(id, "卡片$id", TemplateKind.EXPRESS, rule)

    private fun phase(controller: PrototypeController, id: String): RunPhase? =
        controller.state.value.cards.first { it.id == id }.run?.phase
}
