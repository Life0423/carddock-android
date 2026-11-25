package com.life0423.carddock.ai

import com.life0423.carddock.cainiaoEmptyTemplate
import com.life0423.carddock.core.port.CompileResult
import com.life0423.carddock.demo.TemplateCatalog
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 内置规则无需模型；模型返回的编号只能命中本机真实能力目录。 */
class TemplateRuleCompilerTest {
    private val demo = TemplateCatalog.templates.first()
    private val templates = TemplateCatalog.templates + cainiaoEmptyTemplate

    @Test
    fun explicitlySelectedTemplateRunsWithoutModel() = runTest {
        val compiler = TemplateRuleCompiler(templates) { _, _ -> error("不应联网") }
        assertEquals(CompileResult.Success(demo), compiler.compile(demo.examplePrompt))
        assertEquals(CompileResult.Success(cainiaoEmptyTemplate), compiler.compile(cainiaoEmptyTemplate.examplePrompt))
    }

    @Test
    fun inventedAndDemoTemplateIdsCannotFulfillRealRequests() = runTest {
        val demoChoice = TemplateRuleCompiler(templates) { _, _ -> """{"templateId":"${demo.id}"}""" }
        assertTrue(demoChoice.compile("查真实的菜鸟取件码") is CompileResult.Unsupported)
        assertTrue(demoChoice.compile("查看我的快递取件码和取件位置") is CompileResult.Unsupported)
        assertEquals(CompileResult.Success(demo), demoChoice.compile("用演示数据查看快递"))
        val invented = TemplateRuleCompiler(templates) { _, _ -> """{"templateId":"checkout"}""" }
        assertTrue(invented.compile("下单") is CompileResult.Unsupported)
    }
}
