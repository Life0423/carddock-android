package com.life0423.carddock.core.session

import com.life0423.carddock.core.engine.RuleEngine
import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.model.RunInfo
import com.life0423.carddock.core.model.RunPhase
import com.life0423.carddock.core.model.TemplateDefinition
import com.life0423.carddock.core.model.TemplateKind
import com.life0423.carddock.core.port.CardStore
import com.life0423.carddock.core.port.DeviceGateway
import com.life0423.carddock.core.port.DeviceResponse
import com.life0423.carddock.core.port.DeviceSnapshot
import com.life0423.carddock.core.rule.RuleSpec
import com.life0423.carddock.core.rule.RuleStep
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 修复轮边界：提交后异常再遇取消，以及恢复写失败后的重试。
 * 存储替身只注入故障，集合和协调器走生产实现。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FixroundBoundaryTest {
    private val rule = RuleSpec(
        id = "r",
        sourceId = "source",
        targetKey = "fixed",
        steps = listOf(
            RuleStep.AssertTarget("fixed"),
            RuleStep.ReadField("status", "status", "状态"),
        ),
    )
    private val old = CardRecord("user", "用户卡", TemplateKind.EXPRESS, rule)
    private val extra = old.copy(id = "new", title = "新卡")

    @Test
    fun cancelledCallerWithPostcommitExceptionMustNotSkipReconciliation() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var disk = listOf(old)
        var inject = true
        val store = object : CardStore {
            override suspend fun load(): List<CardRecord> {
                currentCoroutineContext().ensureActive()
                return disk
            }

            override suspend fun save(cards: List<CardRecord>) {
                if (inject) {
                    entered.complete(Unit)
                    release.await()
                    disk = cards
                    throw IOException("postcommit failure")
                }
                disk = cards
            }
        }
        val collection = CardCollection(store, {}, { 100 })
        collection.initialize(emptyList())
        val task = launch { collection.update { it + extra } }
        runCurrent()
        assertTrue(entered.isCompleted)
        task.cancel()
        release.complete(Unit)
        runCurrent()
        inject = false
        collection.update { list -> list.map { card -> card.copy(title = "下一次写入") } }
        assertEquals("已提交新卡必须保留，或应暂停旧内存继续写入", listOf("user", "new"), disk.map { it.id })
    }

    @Test
    fun retryAfterRecoveryWriteFailureMustNotReintroduceOrphanRunningPhase() = runTest {
        var disk = listOf(old.copy(run = RunInfo("before-process-death", RunPhase.RUNNING)))
        val store = object : CardStore {
            override suspend fun load() = disk
            override suspend fun save(cards: List<CardRecord>) {
                throw IOException("disk remains unwritable")
            }
        }
        val collection = CardCollection(store, {}, { 100 })
        collection.initialize(emptyList())
        assertEquals(RunPhase.INTERRUPTED, collection.find(old.id)!!.run!!.phase)
        var deviceCalls = 0
        val runs = RunCoordinator(
            collection,
            RuleEngine(object : DeviceGateway {
                override suspend fun inspect(sourceId: String, targetKey: String): DeviceResponse {
                    deviceCalls += 1
                    return DeviceResponse.Ready(DeviceSnapshot("source", "fixed", mapOf("status" to "ok")))
                }
            }),
            Mutex(),
            backgroundScope,
            { 100 },
            { "new-run" },
        )
        runs.start(old.id)
        runCurrent()
        val phase = collection.find(old.id)!!.run!!.phase
        runs.cancel(old.id)
        runCurrent()
        assertEquals(0, deviceCalls)
        assertFalse("没有活动 job，不得复活磁盘旧 RUNNING", phase in setOf(RunPhase.RUNNING, RunPhase.QUEUED))
        assertFalse(
            collection.find(old.id)!!.run!!.phase in setOf(RunPhase.RUNNING, RunPhase.QUEUED),
        )
    }
}
