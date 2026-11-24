package com.life0423.carddock.core.port

import com.life0423.carddock.core.model.PhoneCapability

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

/** 首轮在独立后台屏学到的模板和当轮取值。常规规则引擎会重新打开来源并逐字段核对。 */
data class DiscoveredTemplate(val template: TemplateDefinition, val observed: Map<String, String>,
    val cutoff: Long? = null, val evidenceDigest: String? = null)

/** 返回 null 表示不支持此需求。读取失败返回原因，调用者保留可重试的草稿。 */
sealed interface DiscoveryResult {
    data class Found(val candidate: DiscoveredTemplate) : DiscoveryResult
    data class Demonstrate(val choices: List<DiscoveredTemplate>) : DiscoveryResult
    /** 用户确认需求理解后才进入独立试查，方案本身没有已核验数据。 */
    data class Plans(val choices: List<TemplateDefinition>, val question: String) : DiscoveryResult
    data class Incomplete(val reason: String, val missingCapability: PhoneCapability? = null) : DiscoveryResult
}
