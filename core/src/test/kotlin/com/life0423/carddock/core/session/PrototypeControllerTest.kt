package com.life0423.carddock.core.session

import com.life0423.carddock.core.engine.RuleEngine
import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.model.CardSnapshot
import com.life0423.carddock.core.model.FieldValue
import com.life0423.carddock.core.model.RunInfo
import com.life0423.carddock.core.model.RunPhase
import com.life0423.carddock.core.model.TemplateDefinition
import com.life0423.carddock.core.model.TemplateKind
import com.life0423.carddock.core.rule.RuleSpec
import com.life0423.carddock.core.rule.RuleStep
import com.life0423.carddock.core.testing.FixedCompiler
import com.life0423.carddock.core.testing.MemoryCardStore
import com.life0423.carddock.core.testing.ObservedDevice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrototypeControllerTest {
    @Test fun `first launch seeds cards but an intentionally empty collection stays empty`() = runTest {
        val first = fixture()
        first.initialize()
        assertEquals(2, first.state.cards.size)
        assertNotNull(first.store.disk)
        val empty = fixture(emptyList())
        empty.initialize()
        assertTrue(empty.state.cards.isEmpty())
    }

    @Test fun `relaunch interrupts active runs without dropping verified data`() = runTest {
        val template = templates.first()
        val stored = listOf(RunPhase.QUEUED, RunPhase.RUNNING, RunPhase.NEEDS_USER).mapIndexed { i, phase ->
            CardRecord("card-$i", "旧卡片", template.kind, template.rule, oldSnapshot,
                RunInfo("run-$i", phase))
        }
        val fixture = fixture(stored)
        fixture.initialize()
        fixture.state.cards.forEach {
            assertEquals(RunPhase.INTERRUPTED, it.run?.phase)
            assertEquals(oldSnapshot, it.snapshot)
        }
        assertEquals(fixture.state.cards, fixture.store.disk)
    }

    @Test fun `refresh failure and takeover preserve last successful snapshot and timestamp`() = runTest {
        val fixture = fixture()
        fixture.initialize()
        val id = fixture.state.cards.first().id
        fixture.actions.refreshCard(id)
        finishOperations()
        val previous = fixture.state.cards.first().snapshot
        assertNotNull(previous)
        for (scenario in listOf(DemoScenario.READ_FAILURE, DemoScenario.NEEDS_USER, DemoScenario.WRONG_TARGET)) {
            fixture.actions.changeScenario(scenario)
            fixture.actions.refreshCard(id)
            finishOperations()
            val card = fixture.state.cards.first()
            assertEquals(previous, card.snapshot)
            assertEquals(if (scenario == DemoScenario.NEEDS_USER) RunPhase.NEEDS_USER else RunPhase.FAILED,
                card.run?.phase)
        }
    }

    @Test fun `duplicate refresh starts one operation and all cards share one device lane`() = runTest {
        val fixture = fixture()
        fixture.initialize()
        val ids = fixture.state.cards.map { it.id }
        fixture.actions.refreshCard(ids[0])
        fixture.actions.refreshCard(ids[0])
        fixture.actions.refreshCard(ids[1])
        fixture.actions.openCreator(templates.first())
        fixture.actions.trialDraft()
        finishOperations(4_000)
        assertEquals(3, fixture.device.calls)
        assertEquals(1, fixture.device.maxConcurrentCalls)
        assertTrue(fixture.state.cards.all { it.run?.phase == RunPhase.SUCCEEDED })
        assertEquals(RunPhase.SUCCEEDED, fixture.state.draft?.phase)
    }

    @Test fun `cancel stops an active read and preserves the previous valid result`() = runTest {
        val fixture = fixture(listOf(savedCard()))
        fixture.initialize()
        fixture.actions.refreshCard("saved")
        runCurrent()
        assertEquals(RunPhase.RUNNING, fixture.state.cards.single().run?.phase)
        fixture.actions.cancelCard("saved")
        runCurrent()
        val card = fixture.state.cards.single()
        assertEquals(RunPhase.CANCELLED, card.run?.phase)
        assertEquals(oldSnapshot, card.snapshot)
        assertEquals(1, fixture.device.cancelledCalls)
    }

    @Test fun `queued cancellation never reaches the device`() = runTest {
        val fixture = fixture()
        fixture.initialize()
        val ids = fixture.state.cards.map { it.id }
        fixture.actions.refreshCard(ids[0])
        fixture.actions.refreshCard(ids[1])
        runCurrent()
        fixture.actions.cancelCard(ids[1])
        finishOperations()
        assertEquals(1, fixture.device.calls)
        assertEquals(RunPhase.CANCELLED, fixture.state.cards[1].run?.phase)
    }

    @Test fun `cancelling a user handoff also works after the device job has ended`() = runTest {
        val fixture = fixture(listOf(savedCard()))
        fixture.initialize()
        fixture.actions.changeScenario(DemoScenario.NEEDS_USER)
        fixture.actions.refreshCard("saved")
        finishOperations()
        assertEquals(RunPhase.NEEDS_USER, fixture.state.cards.single().run?.phase)
        fixture.actions.cancelCard("saved")
        runCurrent()
        assertEquals(RunPhase.CANCELLED, fixture.state.cards.single().run?.phase)
        assertEquals(oldSnapshot, fixture.state.cards.single().snapshot)
        assertEquals(1, fixture.device.calls)
    }

    @Test fun `only a successful unchanged trial can be saved and repeated save is deduplicated`() = runTest {
        val fixture = fixture(emptyList())
        fixture.initialize()
        fixture.actions.openCreator(templates.first())
        fixture.actions.saveDraft()
        runCurrent()
        assertTrue(fixture.state.cards.isEmpty())
        fixture.actions.trialDraft()
        finishOperations()
        assertEquals(RunPhase.SUCCEEDED, fixture.state.draft?.phase)
        fixture.actions.changePrompt("修改后的快递需求")
        fixture.actions.saveDraft()
        runCurrent()
        assertNull(fixture.state.draft?.preview)
        assertTrue(fixture.state.cards.isEmpty())
        fixture.actions.trialDraft()
        finishOperations()
        fixture.actions.changeTitle("  我的取件  ")
        fixture.actions.saveDraft()
        fixture.actions.saveDraft()
        runCurrent()
        assertEquals("我的取件", fixture.state.cards.single().title)
        assertNotNull(fixture.state.cards.single().snapshot)
        assertNull(fixture.state.draft)
    }

    @Test fun `closing a trial cancels work and a reopened creator has no stale result`() = runTest {
        val fixture = fixture()
        fixture.initialize()
        fixture.actions.openCreator(templates.first())
        fixture.actions.trialDraft()
        runCurrent()
        fixture.actions.closeCreator()
        fixture.actions.openCreator(templates.last())
        finishOperations()
        assertEquals(templates.last().examplePrompt, fixture.state.draft?.prompt)
        assertEquals(RunPhase.IDLE, fixture.state.draft?.phase)
        assertNull(fixture.state.draft?.preview)
        assertEquals(1, fixture.device.cancelledCalls)
    }

    @Test fun `simulated takeover starts a fresh verified observation`() = runTest {
        val fixture = fixture()
        fixture.initialize()
        fixture.actions.changeScenario(DemoScenario.NEEDS_USER)
        fixture.actions.openCreator(templates.first())
        fixture.actions.trialDraft()
        finishOperations()
        assertEquals(RunPhase.NEEDS_USER, fixture.state.draft?.phase)
        fixture.actions.trialDraft()
        finishOperations()
        assertEquals(DemoScenario.NORMAL, fixture.state.scenario)
        assertEquals(RunPhase.SUCCEEDED, fixture.state.draft?.phase)
        assertEquals(2, fixture.device.calls)
    }

    @Test fun `failed final persistence never exposes an uncommitted new snapshot`() = runTest {
        val fixture = fixture(listOf(savedCard()))
        fixture.initialize()
        fixture.actions.refreshCard("saved")
        runCurrent()
        fixture.store.failWrite = true
        finishOperations()
        val card = fixture.state.cards.single()
        assertEquals(oldSnapshot, card.snapshot)
        assertEquals(RunPhase.FAILED, card.run?.phase)
        assertEquals(oldSnapshot, fixture.store.disk!!.single().snapshot)
        assertNotNull(fixture.state.notice)
    }

    @Test fun `unreadable data is preserved until the user explicitly resets`() = runTest {
        val fixture = fixture()
        fixture.store.failRead = true
        fixture.initialize()
        assertTrue(fixture.state.cards.isEmpty())
        assertNull(fixture.store.disk)
        fixture.actions.openCreator(templates.first())
        fixture.actions.trialDraft()
        finishOperations()
        fixture.actions.saveDraft()
        runCurrent()
        assertNull(fixture.store.disk)
        fixture.actions.resetDemo()
        runCurrent()
        assertEquals(2, fixture.state.cards.size)
        assertNotNull(fixture.store.disk)
    }

    @Test fun `deleting a running card cannot resurrect it when the read completes`() = runTest {
        val fixture = fixture()
        fixture.initialize()
        val id = fixture.state.cards.first().id
        fixture.actions.refreshCard(id)
        runCurrent()
        fixture.actions.deleteCard(id)
        finishOperations()
        assertFalse(fixture.state.cards.any { it.id == id })
        assertFalse(fixture.store.disk!!.any { it.id == id })
    }

    private fun TestScope.fixture(stored: List<CardRecord>? = null): Fixture {
        val store = MemoryCardStore(stored)
        val device = ObservedDevice(templates.associate { it.rule.sourceId to it.rule.targetKey })
        var id = 0
        // 控制器挂在 backgroundScope 上，用例结束会取消它。设备和 now 都用虚拟时钟，避免两套时间。
        val controller = PrototypeController(
            store, FixedCompiler(templates.first()), RuleEngine(device) { testScheduler.currentTime }, templates,
            backgroundScope, device::setScenario, now = { testScheduler.currentTime }, newId = { "id-${++id}" },
        )
        return Fixture(controller, store, device, this)
    }

    private fun TestScope.finishOperations(time: Long = 2_000) { advanceTimeBy(time); runCurrent() }

    private data class Fixture(
        val controller: PrototypeController,
        val store: MemoryCardStore,
        val device: ObservedDevice,
        val test: TestScope,
    ) {
        val state get() = controller.state.value
        val actions get() = controller.actions
        suspend fun initialize() { controller.initialize(); test.runCurrent() }
    }

    private companion object {
        val oldSnapshot = CardSnapshot(listOf(FieldValue("status", "状态", "旧结果")), 100)
        val templates = listOf(TemplateKind.EXPRESS, TemplateKind.DELIVERY).map { kind ->
            val sourceId = "demo.${kind.name}"
            TemplateDefinition(kind.name, "示例${kind.name}", "演示", kind, "示例需求${kind.name}",
                RuleSpec(id = kind.name, sourceId = sourceId, targetKey = kind.name, steps = listOf(
                    RuleStep.AssertTarget(kind.name), RuleStep.ReadField("status", "status", "状态"),
                    RuleStep.RequireField("status"),
                )))
        }
        fun savedCard() = templates.first().let {
            CardRecord("saved", "已有卡片", it.kind, it.rule, oldSnapshot, RunInfo("old", RunPhase.SUCCEEDED))
        }
    }
}
