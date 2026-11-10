package com.life0423.carddock.core.port

import com.life0423.carddock.core.model.TemplateDefinition

/** Success 只是候选模板，控制器在引擎试运行通过之前不会把它存成卡片。 */
sealed interface CompileResult {
    data class Success(val template: TemplateDefinition) : CompileResult
    data class Unsupported(val message: String) : CompileResult
}

/**
 * A successful compilation is still a candidate: it must pass a real engine trial before saving.
 * 实现不得执行设备读取。空白或无法分流时返回 Unsupported，不要抛给界面。
 */
interface RuleCompiler {
    suspend fun compile(prompt: String): CompileResult
}
