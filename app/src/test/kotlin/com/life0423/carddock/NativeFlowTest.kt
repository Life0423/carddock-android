package com.life0423.carddock

import com.life0423.carddock.ai.MockRuleCompiler
import com.life0423.carddock.core.engine.RuleEngine
import com.life0423.carddock.core.port.CompileResult
import com.life0423.carddock.core.session.PrototypeController
import com.life0423.carddock.core.model.RunPhase
import com.life0423.carddock.core.testing.MemoryCardStore
import com.life0423.carddock.demo.TemplateCatalog
import com.life0423.carddock.platform.CainiaoDeviceGateway
import com.life0423.carddock.platform.CainiaoObservation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** 用迁入的识别器、引擎、控制器验证完整保存与失败保留链路；输入是合成页面而非真机证据。 */
@OptIn(ExperimentalCoroutinesApi::class)
class NativeFlowTest {
    @Test fun nativeTrialSaveRefreshAndRestoreKeepVerifiedSource() = runTest {
        var connected = true
        val gateway = CainiaoDeviceGateway({ connected }, {
            CainiaoObservation("com.cainiao.wireless", setOf("取包裹", "暂无待取包裹"), false)
        })
        val templates = TemplateCatalog.templates + cainiaoEmptyTemplate
        val store = MemoryCardStore(emptyList())
        val controller = PrototypeController(store, MockRuleCompiler(templates), RuleEngine(gateway, now = { 123L }), templates, backgroundScope)
        controller.initialize()
        controller.actions.openCreator(cainiaoEmptyTemplate)
        controller.actions.trialDraft()
        testScheduler.runCurrent()
        assertEquals(RunPhase.SUCCEEDED, controller.state.value.draft?.phase)
        controller.actions.saveDraft()
        testScheduler.runCurrent()
        val saved = store.disk!!.single()
        assertEquals("菜鸟 · 当前页面", saved.snapshot?.sourceLabel)
        assertEquals("暂无待取包裹", saved.snapshot?.fields?.single()?.value)
        connected = false
        controller.actions.refreshCard(saved.id)
        testScheduler.runCurrent()
        val after = store.disk!!.single()
        assertEquals(RunPhase.NEEDS_USER, after.run?.phase)
        assertEquals(saved.snapshot, after.snapshot)
        val restored = PrototypeController(store, MockRuleCompiler(templates), RuleEngine(gateway), templates, backgroundScope)
        restored.initialize()
        testScheduler.runCurrent()
        assertEquals(saved.snapshot, restored.state.value.cards.single().snapshot)
    }

    @Test fun nativeRequestsNeverBecomeDemoPickupCodes() = runTest {
        val compiler = MockRuleCompiler(TemplateCatalog.templates + cainiaoEmptyTemplate)
        assertEquals(CompileResult.Success(cainiaoEmptyTemplate), compiler.compile(cainiaoEmptyTemplate.examplePrompt))
        assertTrue(compiler.compile("查看菜鸟取件码") is CompileResult.Unsupported)
        assertTrue(compiler.compile("核验菜鸟暂无待取包裹并支付") is CompileResult.Unsupported)
        assertEquals(CompileResult.Success(TemplateCatalog.templates.first()), compiler.compile(TemplateCatalog.templates.first().examplePrompt))
    }
}
