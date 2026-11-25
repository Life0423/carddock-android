package com.life0423.carddock.ai

import com.life0423.carddock.core.model.TemplateDefinition
import com.life0423.carddock.core.port.CompileResult
import com.life0423.carddock.core.port.RuleCompiler
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** 模型只从已接入的模板里匹配需求。模板本身仍须通过原有试运行。 */
internal class TemplateRuleCompiler(
    private val templates: List<TemplateDefinition>,
    private val request: suspend (String, String) -> String,
) : RuleCompiler {
    override suspend fun compile(prompt: String): CompileResult {
        val demand = prompt.trim()
        if (demand.isEmpty()) return CompileResult.Unsupported("请先描述要执行的任务。")
        // 真实查询不能暗中换成演示。仅明确要求演示时，才让模型考虑这类模板。
        val available = templates.filter { !it.rule.sourceId.startsWith("demo.") || "演示" in demand }
        available.firstOrNull { it.examplePrompt == demand }?.let { return CompileResult.Success(it) }
        val instruction = """
            只匹配一个完全符合需求的现有只读模板。不要省略限定条件。
            如果涉及修改、下单、支付，或目录没有满足需求的模板，返回 {"templateId":null}。
            其它情况只返回 {"templateId":"目录中的原样编号"}。目录：
            ${available.joinToString("\n") { "${it.id} | ${it.title} | ${it.description}" }}
        """.trimIndent()
        val answer = try {
            val reply = request(instruction, modelText(demand)).trim()
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            Json.decodeFromString<TemplateAnswer>(reply)
        } catch (failure: WorkspaceModelException) {
            return CompileResult.Unsupported(failure.message.orEmpty())
        } catch (_: SerializationException) {
            return CompileResult.Unsupported("模型未返回有效的模板选择。")
        }
        return available.firstOrNull { it.id == answer.templateId }?.let(CompileResult::Success)
            ?: CompileResult.Unsupported("当前模板无法执行该任务。")
    }
}

@Serializable
private data class TemplateAnswer(val templateId: String?)
