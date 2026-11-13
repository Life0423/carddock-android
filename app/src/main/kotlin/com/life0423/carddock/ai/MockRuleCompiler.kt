package com.life0423.carddock.ai

import com.life0423.carddock.core.model.TemplateDefinition
import com.life0423.carddock.core.model.TemplateKind
import com.life0423.carddock.core.port.CompileResult
import com.life0423.carddock.core.port.RuleCompiler
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Keyword routing for the prototype, not an AI model or an arbitrary rule generator.
 * 只在传入的模板里做关键词分流，不会生成新规则。构造时拷贝列表，之后调用方改原列表不影响匹配。
 * 先拒绝支付、下单等写操作，再要求恰好命中一种模板。
 */
class MockRuleCompiler(templates: List<TemplateDefinition>) : RuleCompiler {
    private val availableTemplates = templates.toList()

    override suspend fun compile(prompt: String): CompileResult {
        currentCoroutineContext().ensureActive()
        val request = prompt.trim().lowercase()
        if (request.isEmpty()) return CompileResult.Unsupported("请先描述想查看的信息，例如“查看快递取件码”。")
        if (unsupportedActions.any(request::contains)) {
            return CompileResult.Unsupported("当前原型只演示信息读取，暂不支持下单、支付或修改数据。")
        }

        availableTemplates.singleOrNull {
            !it.rule.sourceId.startsWith("demo.") && it.examplePrompt.lowercase() == request
        }?.let { return CompileResult.Success(it) }
        if ("美团" in request || "京东" in request) {
            return CompileResult.Unsupported("请选择已核验的美团或京东模板。")
        }

        // 真机请求只匹配已经核验的固定模板，不回退到模拟取件码。
        if ("菜鸟" in request) {
            val template = availableTemplates.singleOrNull { it.id == "cainiao-empty" }
            return if (template != null && request == template.examplePrompt) CompileResult.Success(template)
            else CompileResult.Unsupported("菜鸟目前仅支持“核验菜鸟暂无待取包裹”，暂不读取真实取件码。")
        }

        // 命中两种及以上就拒绝，避免一条描述悄悄落到其中一个模板。
        val matchingKinds = keywords.filterValues { words -> words.any { matches(request, it) } }.keys
        if (matchingKinds.size > 1) {
            return CompileResult.Unsupported("这段描述包含多个功能，请先选择快递、外卖或出行中的一个。")
        }
        if (matchingKinds.isEmpty()) {
            return CompileResult.Unsupported("暂未匹配到模板。当前支持快递取件、外卖进度和出行安排。")
        }
        val template = availableTemplates.singleOrNull { it.kind == matchingKinds.single() && it.rule.sourceId.startsWith("demo.") }
            ?: return CompileResult.Unsupported("这个演示模板暂不可用，请选择其他模板。")
        return CompileResult.Success(template)
    }

    /**
     * 英文关键词使用正则的词边界，避免 train 命中 training。
     * 中文关键词直接做子串匹配，以便连续的中文描述能命中；这里不做自然语言分词。
     */
    private fun matches(request: String, keyword: String): Boolean =
        if (keyword.first() in 'a'..'z') {
            Regex("\\b${Regex.escape(keyword)}\\b").containsMatchIn(request)
        } else {
            keyword in request
        }

    private companion object {
        val keywords = mapOf(
            TemplateKind.EXPRESS to listOf("快递", "取件", "包裹", "parcel", "pickup"),
            TemplateKind.DELIVERY to listOf("外卖", "送餐", "delivery", "takeaway"),
            TemplateKind.TRAVEL to listOf("出行", "行程", "车票", "高铁", "火车", "检票", "travel", "train"),
        )
        val unsupportedActions = listOf("支付", "付款", "转账", "下单", "购买", "抢票", "退款", "删除", "取消订单", "修改")
    }
}
