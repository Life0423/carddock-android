package com.life0423.carddock.core.port

import com.life0423.carddock.core.model.TemplateDefinition

sealed interface CompileResult {
    data class Success(val template: TemplateDefinition) : CompileResult
    data class Unsupported(val message: String) : CompileResult
}

/** A successful compilation is still a candidate: it must pass a real engine trial before saving. */
interface RuleCompiler {
    suspend fun compile(prompt: String): CompileResult
}
