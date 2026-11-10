package com.life0423.carddock.data

import android.app.Application
import android.content.Context
import androidx.core.util.AtomicFile
import com.life0423.carddock.core.engine.RuleEngine
import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.model.RunPhase
import com.life0423.carddock.core.model.TemplateDefinition
import com.life0423.carddock.core.model.TemplateKind
import com.life0423.carddock.core.port.CompileResult
import com.life0423.carddock.core.port.DeviceGateway
import com.life0423.carddock.core.port.DeviceResponse
import com.life0423.carddock.core.port.DeviceSnapshot
import com.life0423.carddock.core.port.RuleCompiler
import com.life0423.carddock.core.rule.RuleSpec
import com.life0423.carddock.core.rule.RuleStep
import com.life0423.carddock.core.session.PrototypeController
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 用 AtomicFile 子类只替换 I/O 接缝。save、load 和 PrototypeController 仍走生产实现。
 * 读回失败注入发生在 finishWrite 真正返回之后。
 * 用普通 Application，避免真实 CardDockApplication 在同一文件上并发写。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AtomicFailureTest {
    private lateinit var context: Context
    private lateinit var base: File
    private val json = Json { encodeDefaults = true }
    private val rule = RuleSpec(
        id = "r",
        sourceId = "source",
        targetKey = "fixed",
        steps = listOf(
            RuleStep.AssertTarget("fixed"),
            RuleStep.ReadField("status", "status", "状态"),
        ),
    )
    private val old = CardRecord("user-card", "用户卡", TemplateKind.EXPRESS, rule)

    /** 在真实 AtomicFile 完成 rename 之后，让随后的 openRead 失败或读到别的字节。 */
    private class FaultAtomic(base: File) : AtomicFile(base) {
        var failStart = false
        var failRead = false
        var failReadAfterFinish = false
        var failNextRead = false
        var mismatchAfterFinish: File? = null
        var nextReadFile: File? = null
        var cancelOnStart: Job? = null
        var failedCleanups = 0
        var partialWriteFailure = false
        var syncFailure = false

        override fun startWrite(): FileOutputStream {
            if (failStart) throw IOException("injected startWrite failure")
            if (partialWriteFailure || syncFailure) {
                val temporary = File(baseFile.path + ".new")
                return object : FileOutputStream(temporary) {
                    override fun write(bytes: ByteArray) {
                        if (partialWriteFailure) {
                            super.write(bytes, 0, bytes.size / 2)
                            throw IOException("injected partial-write failure")
                        }
                        super.write(bytes)
                        if (syncFailure) super.close()
                    }
                }
            }
            return super.startWrite().also { cancelOnStart?.cancel() }
        }

        override fun finishWrite(stream: FileOutputStream?) {
            super.finishWrite(stream)
            if (failReadAfterFinish) failNextRead = true
            nextReadFile = mismatchAfterFinish
        }

        override fun openRead(): FileInputStream {
            if (failRead || failNextRead) {
                failNextRead = false
                throw IOException("injected post-rename openRead failure")
            }
            nextReadFile?.let {
                nextReadFile = null
                return FileInputStream(it)
            }
            return super.openRead()
        }

        override fun failWrite(stream: FileOutputStream?) {
            failedCleanups += 1
            super.failWrite(stream)
        }
    }

    private fun store(hook: FaultAtomic? = null): AtomicFileCardStore {
        val created = AtomicFileCardStore(context, json)
        if (hook != null) {
            // 生产字段是私有的；测试只换掉文件对象，不复制保存逻辑。
            AtomicFileCardStore::class.java.getDeclaredField("file").apply {
                isAccessible = true
                set(created, hook)
            }
        }
        return created
    }

    @Before
    fun clean() {
        context = RuntimeEnvironment.getApplication()
        base = File(context.filesDir, "cards-v1.json")
        // 正式路径被各用例共用。目录或残留的 .new/.bak 会让下一次读回比对误失败。
        base.deleteRecursively()
        File(base.path + ".new").delete()
        File(base.path + ".bak").delete()
        AtomicFile(base).delete()
    }

    @Test
    fun injectedLoadReadFailurePreservesBase() = runBlocking {
        store().save(listOf(old))
        val bytes = base.readBytes()
        val hook = FaultAtomic(base)
        hook.failRead = true
        try {
            store(hook).load()
            fail("load must throw")
        } catch (_: IOException) {
        }
        assertArrayEquals(bytes, base.readBytes())
    }

    @Test
    fun injectedStartWriteFailurePreservesOldFile() = runBlocking {
        store().save(listOf(old))
        val bytes = base.readBytes()
        val hook = FaultAtomic(base)
        hook.failStart = true
        try {
            store(hook).save(emptyList())
            fail("save must throw")
        } catch (_: IOException) {
        }
        assertArrayEquals(bytes, base.readBytes())
        assertEquals(0, hook.failedCleanups)
    }

    @Test
    fun cancellationBeforeFinishCleansTemporaryAndKeepsOldFile() = runBlocking {
        store().save(listOf(old))
        val bytes = base.readBytes()
        val hook = FaultAtomic(base)
        // 先把 job 交给 cancelOnStart 再 start。这里的 runBlocking 不会在 launch 返回前执行保存；LAZY 只是让启动发生在赋值之后。
        val task = launch(start = CoroutineStart.LAZY) { store(hook).save(emptyList()) }
        hook.cancelOnStart = task
        task.start()
        task.join()
        assertTrue(task.isCancelled)
        assertArrayEquals(bytes, base.readBytes())
        assertFalse(File(base.path + ".new").exists())
        assertEquals(1, hook.failedCleanups)
    }

    @Test
    fun postRenameReadFailureStillReportsTheCommittedCollection() = runBlocking {
        store().save(listOf(old))
        val hook = FaultAtomic(base)
        hook.failReadAfterFinish = true
        store(hook).save(emptyList())
        assertEquals(emptyList<CardRecord>(), store().load())
    }

    @Test
    fun postRenameReadMismatchStillReportsTheCommittedCollection() = runBlocking {
        store().save(listOf(old))
        val hook = FaultAtomic(base)
        val wrong = File(context.cacheDir, "readback-wrong.json")
        wrong.writeText("wrong bytes")
        hook.mismatchAfterFinish = wrong
        store(hook).save(emptyList())
        assertEquals(emptyList<CardRecord>(), store().load())
    }

    @Test
    fun committedReadbackFailureDoesNotDropTheNewCardOnLaterDelete() = runBlocking {
        store().save(listOf(old))
        val hook = FaultAtomic(base)
        val realStore = store(hook)
        val template = TemplateDefinition("t", "新卡", "演示", TemplateKind.EXPRESS, "快递", rule)
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val controller = PrototypeController(
                realStore,
                object : RuleCompiler {
                    override suspend fun compile(prompt: String) = CompileResult.Success(template)
                },
                RuleEngine(object : DeviceGateway {
                    override suspend fun inspect(sourceId: String, targetKey: String): DeviceResponse =
                        DeviceResponse.Ready(DeviceSnapshot("source", "fixed", mapOf("status" to "真实执行结果")))
                }),
                listOf(template),
                scope,
            )
            controller.initialize()
            withTimeout(5_000) { controller.state.first { !it.loading } }
            controller.actions.openCreator(template)
            controller.actions.trialDraft()
            withTimeout(5_000) { controller.state.first { it.draft?.phase == RunPhase.SUCCEEDED } }
            hook.failReadAfterFinish = true
            controller.actions.saveDraft()
            withTimeout(5_000) { controller.state.first { it.draft == null || it.cards.size > 1 } }
            assertEquals(2, controller.state.value.cards.size)
            assertEquals(2, store().load()!!.size)
            hook.failReadAfterFinish = false
            controller.actions.deleteCard(old.id)
            withTimeout(5_000) { controller.state.first { it.cards.none { card -> card.id == old.id } } }
            val remaining = store().load()!!
            assertEquals(1, remaining.size)
            assertFalse(remaining.any { it.id == old.id })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun partialWriteFailureDeletesNewAndPreservesOldBase() = runBlocking {
        store().save(listOf(old))
        val bytes = base.readBytes()
        val hook = FaultAtomic(base)
        hook.partialWriteFailure = true
        try {
            store(hook).save(emptyList())
            fail("save must throw")
        } catch (_: IOException) {
        }
        assertArrayEquals(bytes, base.readBytes())
        assertFalse(File(base.path + ".new").exists())
        assertEquals(1, hook.failedCleanups)
    }

    @Test
    fun fsyncOnClosedDescriptorFailurePreservesOldBase() = runBlocking {
        store().save(listOf(old))
        val bytes = base.readBytes()
        val hook = FaultAtomic(base)
        hook.syncFailure = true
        try {
            store(hook).save(emptyList())
            fail("save must throw")
        } catch (_: IOException) {
        }
        assertArrayEquals(bytes, base.readBytes())
        assertFalse(File(base.path + ".new").exists())
        assertEquals(1, hook.failedCleanups)
    }
}
