package com.life0423.carddock.data

import android.app.Application
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
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 真实 AtomicFile 与控制器：finishWrite 已经成功，随后一次确认失败，同时取消创建保存任务。
 * 用普通 Application，避免测试进程拉起真实 CardDockApplication。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class FixroundCancelledCommitTest {
    /** 只在 finishWrite 成功后让下一次 baseFileHas 无法确认，并取消正在保存的任务。 */
    private class Hook(base: File) : AtomicFile(base) {
        var enabled = false
        var cannotVerifyOnce = false
        var cancelCreator: (() -> Unit)? = null

        override fun finishWrite(stream: FileOutputStream?) {
            super.finishWrite(stream)
            if (enabled) {
                cannotVerifyOnce = true
                cancelCreator!!()
            }
        }

        override fun getBaseFile(): File {
            val real = super.getBaseFile()
            if (!cannotVerifyOnce) return real
            cannotVerifyOnce = false
            return object : File(real.path) {
                override fun isFile() = false
            }
        }
    }

    @Test
    fun cancellingCreatorDuringAmbiguousCommitMustRetainCommittedNewCard() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val base = File(context.filesDir, "cards-v1.json")
        base.deleteRecursively()
        AtomicFile(base).delete()
        val json = Json { encodeDefaults = true }
        val rule = RuleSpec(
            id = "r",
            sourceId = "source",
            targetKey = "fixed",
            steps = listOf(
                RuleStep.AssertTarget("fixed"),
                RuleStep.ReadField("status", "status", "状态"),
            ),
        )
        val old = CardRecord("user", "旧卡", TemplateKind.EXPRESS, rule)
        val store = AtomicFileCardStore(context, json)
        store.save(listOf(old))
        val hook = Hook(base)
        AtomicFileCardStore::class.java.getDeclaredField("file").apply {
            isAccessible = true
            set(store, hook)
        }
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val template = TemplateDefinition("t", "新卡", "演示", TemplateKind.EXPRESS, "快递", rule)
            val controller = PrototypeController(
                store,
                object : RuleCompiler {
                    override suspend fun compile(prompt: String) = CompileResult.Success(template)
                },
                RuleEngine(object : DeviceGateway {
                    override suspend fun inspect(sourceId: String, targetKey: String): DeviceResponse =
                        DeviceResponse.Ready(DeviceSnapshot("source", "fixed", mapOf("status" to "ok")))
                }),
                listOf(template),
                scope,
            )
            controller.initialize()
            withTimeout(5_000) { controller.state.first { !it.loading } }
            controller.actions.openCreator(template)
            controller.actions.trialDraft()
            withTimeout(5_000) { controller.state.first { it.draft?.phase == RunPhase.SUCCEEDED } }
            var cancelledWork: Job? = null
            hook.cancelCreator = {
                val creatorField = PrototypeController::class.java.getDeclaredField("creator").apply {
                    isAccessible = true
                }
                val creator = creatorField.get(controller)
                val work = creator.javaClass.getDeclaredField("work").apply { isAccessible = true }.get(creator) as Job
                cancelledWork = work
                work.cancel()
            }
            hook.enabled = true
            controller.actions.saveDraft()
            withTimeout(5_000) {
                while (cancelledWork == null) yield()
            }
            cancelledWork!!.join()
            hook.enabled = false
            controller.actions.closeCreator()
            controller.actions.deleteCard(old.id)
            withTimeout(5_000) { controller.state.first { state -> state.cards.none { it.id == old.id } } }
            val diskAfter = AtomicFileCardStore(context, json).load()!!
            assertEquals("已提交的新卡不得因取消后的旧内存覆盖而丢失", 1, diskAfter.size)
        } finally {
            scope.cancel()
            AtomicFile(base).delete()
        }
    }
}
