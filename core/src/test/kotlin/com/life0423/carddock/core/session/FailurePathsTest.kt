package com.life0423.carddock.core.session

import com.life0423.carddock.core.engine.RuleEngine
import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.model.CardSnapshot
import com.life0423.carddock.core.model.FieldValue
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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：读失败、提交前失败、提交后失败，以及取消/异常收尾写失败。
 * 存储替身只在端口注入故障，集合和协调器走生产实现。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FailurePathsTest {
    private val rule = RuleSpec(
        id = "r",
        sourceId = "source",
        targetKey = "observed-fixed",
        steps = listOf(
            RuleStep.AssertTarget("observed-fixed"),
            RuleStep.ReadField("status", "status", "状态"),
        ),
    )
    private val old = CardRecord(
        id = "user-card",
        title = "用户自建卡",
        kind = TemplateKind.EXPRESS,
        rule = rule,
        snapshot = CardSnapshot(listOf(FieldValue("status", "状态", "旧有效数据")), 10),
    )
    private val seed = old.copy(id = "seed", title = "种子卡", snapshot = null)
    private val template = TemplateDefinition("t", "种子卡", "演示", TemplateKind.EXPRESS, "快递", rule)

    /** 能区分提交前失败和提交后失败的内存存储。提交后失败表示磁盘已换成新集合，随后才抛错。 */
    private class FaultStore(var disk: List<CardRecord>?) : CardStore {
        var failRead = false
        var failWrite = false
        var failAfterCommit = false
        var writes = 0

        override suspend fun load(): List<CardRecord>? {
            if (failRead) throw IOException("injected read failure")
            return disk
        }

        override suspend fun save(cards: List<CardRecord>) {
            writes += 1
            if (failWrite) throw IOException("injected precommit write failure")
            disk = cards.toList()
            if (failAfterCommit) throw IOException("injected postcommit readback failure")
        }
    }

    @Test
    fun readFailureDoesNotOverwriteDiskOrAcceptUpdates() = runTest {
        val store = FaultStore(listOf(old))
        store.failRead = true
        val collection = collection(store)
        collection.initialize(listOf(seed))
        assertTrue(collection.state.value.cards.isEmpty())
        assertEquals(listOf(old), store.disk)
        assertFalse(collection.update { it + seed })
        assertEquals(0, store.writes)
    }

    @Test
    fun readSuccessWithoutRecoveryDoesNotWrite() = runTest {
        val store = FaultStore(listOf(old))
        store.failWrite = true
        val collection = collection(store)
        collection.initialize(listOf(seed))
        assertEquals(listOf(old), collection.state.value.cards)
        assertEquals(0, store.writes)
    }

    @Test
    fun recoveryWriteFailureKeepsTheLoadedCardsAndCanBeRetried() = runTest {
        val active = old.copy(run = RunInfo("old-run", RunPhase.RUNNING))
        val store = FaultStore(listOf(active))
        store.failWrite = true
        val notices = mutableListOf<String>()
        val collection = CardCollection(store, { notices += it }, { 100 })
        collection.initialize(listOf(seed))

        val shown = collection.state.value.cards.single()
        assertEquals("user-card", shown.id)
        assertEquals(RunPhase.INTERRUPTED, shown.run?.phase)
        assertEquals("user-card", store.disk!!.single().id)
        assertEquals(1, store.writes)
        assertTrue(notices.none { it.contains("无法读取") })
        assertTrue(notices.any { it.contains("写回") })
        store.failWrite = false
        assertTrue(collection.update { list -> list.map { it.copy(title = "仍是用户卡") } })
        assertEquals("仍是用户卡", store.disk!!.single().title)
        assertEquals("user-card", store.disk!!.single().id)
    }

    @Test
    fun controllerDoesNotReplaceUserCardsWhenRecoveryWriteFails() = runTest {
        val store = FaultStore(listOf(old.copy(run = RunInfo("old-run", RunPhase.RUNNING))))
        store.failWrite = true
        val controller = PrototypeController(
            store,
            compiler(),
            RuleEngine(object : DeviceGateway {
                override suspend fun inspect(sourceId: String, targetKey: String) = error("not called")
            }),
            listOf(template),
            backgroundScope,
        )
        controller.initialize()
        runCurrent()
        assertEquals("user-card", controller.state.value.cards.single().id)
        assertEquals("user-card", store.disk!!.single().id)
        assertFalse(controller.state.value.notice.orEmpty().contains("无法读取"))
    }

    @Test
    fun precommitFailureKeepsDiskAndMemoryTogether() = runTest {
        val store = FaultStore(listOf(old))
        val collection = collection(store)
        collection.initialize(listOf(seed))
        store.failWrite = true
        assertFalse(collection.update { it + seed })
        assertEquals(listOf(old), store.disk)
        assertEquals(listOf(old), collection.state.value.cards)
    }

    @Test
    fun postcommitFailurePublishesTheCommittedCards() = runTest {
        val store = FaultStore(listOf(old))
        val collection = collection(store)
        collection.initialize(listOf(seed))
        store.failAfterCommit = true
        assertTrue(collection.update { it + seed })
        assertEquals(listOf("user-card", "seed"), store.disk!!.map { it.id })
        assertEquals(store.disk, collection.state.value.cards)
        store.failAfterCommit = false
        assertTrue(collection.update { list -> list.map { card -> card.copy(title = card.title + "·改") } })
        assertEquals(listOf("user-card", "seed"), store.disk!!.map { it.id })
    }

    @Test
    fun cancellationWriteFailureLeavesARecognizableTerminalPhase() = runTest {
        val store = FaultStore(listOf(old))
        val collection = collection(store)
        collection.initialize(listOf(seed))
        val runs = RunCoordinator(
            collection,
            RuleEngine(object : DeviceGateway {
                override suspend fun inspect(sourceId: String, targetKey: String): DeviceResponse = awaitCancellation()
            }),
            Mutex(),
            backgroundScope,
            { 100 },
            { "new-run" },
        )
        runs.start(old.id)
        runCurrent()
        assertEquals(RunPhase.RUNNING, collection.find(old.id)!!.run!!.phase)
        store.failWrite = true
        runs.cancel(old.id)
        runCurrent()
        val phase = collection.find(old.id)!!.run!!.phase
        assertTrue(phase == RunPhase.FAILED || phase == RunPhase.CANCELLED)
        assertEquals(old.snapshot, collection.find(old.id)!!.snapshot)
        store.failWrite = false
        runs.start(old.id)
        runCurrent()
        runs.cancel(old.id)
        runCurrent()
        assertEquals(RunPhase.CANCELLED, collection.find(old.id)!!.run!!.phase)
    }

    @Test
    fun queuedCancellationWriteFailureDoesNotStayQueued() = runTest {
        val store = FaultStore(listOf(old))
        val collection = collection(store)
        collection.initialize(listOf(seed))
        val runs = RunCoordinator(
            collection,
            RuleEngine(object : DeviceGateway {
                override suspend fun inspect(sourceId: String, targetKey: String) = error("must stay queued")
            }),
            Mutex(locked = true),
            backgroundScope,
            { 100 },
            { "new-run" },
        )
        runs.start(old.id)
        runCurrent()
        assertEquals(RunPhase.QUEUED, collection.find(old.id)!!.run!!.phase)
        store.failWrite = true
        runs.cancel(old.id)
        runCurrent()
        val phase = collection.find(old.id)!!.run!!.phase
        assertTrue(phase == RunPhase.FAILED || phase == RunPhase.CANCELLED)
    }

    @Test
    fun exceptionCleanupWriteFailureDoesNotStayRunning() = runTest {
        val store = FaultStore(listOf(old))
        val collection = collection(store)
        collection.initialize(listOf(seed))
        var clockFails = false
        val engine = RuleEngine(object : DeviceGateway {
            override suspend fun inspect(sourceId: String, targetKey: String): DeviceResponse {
                store.failWrite = true
                clockFails = true
                return DeviceResponse.Ready(DeviceSnapshot("source", "observed-fixed", mapOf("status" to "新值")))
            }
        }) { if (clockFails) throw IOException("injected clock exception") else 100 }
        val runs = RunCoordinator(collection, engine, Mutex(), backgroundScope, { 100 }, { "new-run" })
        runs.start(old.id)
        runCurrent()
        val phase = collection.find(old.id)!!.run!!.phase
        assertTrue(phase == RunPhase.FAILED || phase == RunPhase.CANCELLED)
        assertEquals(old.snapshot, collection.find(old.id)!!.snapshot)
    }

    @Test
    fun normalResultWriteFailureKeepsThePreviousSnapshot() = runTest {
        val store = FaultStore(listOf(old))
        val collection = collection(store)
        collection.initialize(listOf(seed))
        val runs = RunCoordinator(
            collection,
            RuleEngine(object : DeviceGateway {
                override suspend fun inspect(sourceId: String, targetKey: String): DeviceResponse {
                    store.failWrite = true
                    return DeviceResponse.Ready(DeviceSnapshot("source", "observed-fixed", mapOf("status" to "新值")))
                }
            }),
            Mutex(),
            backgroundScope,
            { 100 },
            { "new-run" },
        )
        runs.start(old.id)
        runCurrent()
        assertEquals(RunPhase.FAILED, collection.find(old.id)!!.run!!.phase)
        assertEquals(old.snapshot, collection.find(old.id)!!.snapshot)
    }

    @Test
    fun cancellationWithWritableStoreEndsCancelled() = runTest {
        val store = FaultStore(listOf(old))
        val collection = collection(store)
        collection.initialize(listOf(seed))
        val runs = RunCoordinator(
            collection,
            RuleEngine(object : DeviceGateway {
                override suspend fun inspect(sourceId: String, targetKey: String): DeviceResponse = awaitCancellation()
            }),
            Mutex(),
            backgroundScope,
            { 100 },
            { "new-run" },
        )
        runs.start(old.id)
        runCurrent()
        runs.cancel(old.id)
        runCurrent()
        assertEquals(RunPhase.CANCELLED, collection.find(old.id)!!.run!!.phase)
    }

    @Test
    fun unchangedUpdateReturnsFalseWithoutWriting() = runTest {
        val store = FaultStore(listOf(old))
        val collection = collection(store)
        collection.initialize(listOf(seed))
        assertFalse(collection.update { it })
        assertEquals(0, store.writes)
    }

    @Test
    fun cancellationDuringCommitStillPublishesDiskAndMemoryTogether() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var disk = listOf(old)
        val store = object : CardStore {
            override suspend fun load() = disk
            override suspend fun save(cards: List<CardRecord>) {
                entered.complete(Unit)
                release.await()
                disk = cards
            }
        }
        val collection = CardCollection(store, {}, { 100 })
        collection.initialize(listOf(seed))
        val task = launch { collection.update { it + seed } }
        runCurrent()
        assertTrue(entered.isCompleted)
        task.cancel()
        release.complete(Unit)
        runCurrent()
        assertEquals(2, disk.size)
        assertEquals(disk, collection.state.value.cards)
        assertTrue(task.isCancelled)
    }

    @Test
    fun exceptionWithWritableCleanupEndsFailed() = runTest {
        val store = FaultStore(listOf(old))
        val collection = collection(store)
        collection.initialize(listOf(seed))
        val engine = RuleEngine(object : DeviceGateway {
            override suspend fun inspect(sourceId: String, targetKey: String) =
                DeviceResponse.Ready(DeviceSnapshot("source", "observed-fixed", mapOf("status" to "新值")))
        }) { throw IOException("injected unexpected clock exception") }
        val runs = RunCoordinator(collection, engine, Mutex(), backgroundScope, { 100 }, { "new-run" })
        runs.start(old.id)
        runCurrent()
        assertEquals(RunPhase.FAILED, collection.find(old.id)!!.run!!.phase)
    }

    private fun TestScope.collection(store: FaultStore) = CardCollection(store, {}, { 100 })

    private fun compiler() = object : RuleCompiler {
        override suspend fun compile(prompt: String) = CompileResult.Success(template)
    }
}
