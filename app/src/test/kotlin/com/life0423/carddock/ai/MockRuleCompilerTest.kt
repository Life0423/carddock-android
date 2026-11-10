package com.life0423.carddock.ai

import com.life0423.carddock.core.engine.EngineResult
import com.life0423.carddock.core.engine.RuleEngine
import com.life0423.carddock.core.model.TemplateKind
import com.life0423.carddock.core.port.CompileResult
import com.life0423.carddock.demo.DemoDeviceGateway
import com.life0423.carddock.demo.TemplateCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MockRuleCompilerTest {
    private val compiler = MockRuleCompiler(TemplateCatalog.templates)

    @Test
    fun everyExampleCompilesToAWorkingDemonstrationRule() = runBlocking {
        val engine = RuleEngine(DemoDeviceGateway(suspendDelayMillis = 0L), now = { 1000L })

        for (template in TemplateCatalog.templates) {
            val compiled = compiler.compile(template.examplePrompt)
            assertEquals(CompileResult.Success(template), compiled)
            val result = engine.execute((compiled as CompileResult.Success).template.rule)
            assertTrue(template.id, result is EngineResult.Success)
            val snapshot = (result as EngineResult.Success).snapshot
            assertEquals(3, snapshot.fields.size)
            assertTrue(snapshot.fields.all { it.value.isNotBlank() })
            assertEquals(1000L, snapshot.fetchedAt)
        }
    }

    @Test
    fun ambiguousUnknownAndWriteRequestsAreExplicitlyUnsupported() = runBlocking {
        val requests = listOf(" ", "做一张卡片", "查看天气", "同时查看快递和外卖", "帮我支付外卖订单", "修改火车票", "show my training plan")

        for (request in requests) {
            val result = compiler.compile(request)
            assertTrue(request, result is CompileResult.Unsupported)
            assertTrue((result as CompileResult.Unsupported).message.isNotBlank())
        }
    }

    @Test
    fun selectingATemplateDoesNotDependOnCatalogOrderOrLetterCase() = runBlocking {
        val reordered = MockRuleCompiler(TemplateCatalog.templates.reversed())
        val result = reordered.compile("  View my PARCEL pickup information  ")

        assertTrue(result is CompileResult.Success)
        assertEquals(TemplateKind.EXPRESS, (result as CompileResult.Success).template.kind)
    }

    @Test
    fun aMissingTemplateIsNotInvented() = runBlocking {
        val withoutTravel = MockRuleCompiler(TemplateCatalog.templates.filterNot { it.kind == TemplateKind.TRAVEL })

        assertTrue(withoutTravel.compile("查看出行安排") is CompileResult.Unsupported)
    }
}
