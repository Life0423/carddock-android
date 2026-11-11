package com.wangzan101.mika.experiment

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class RuleTemplate(
    val schemaVersion: Int,
    val ruleId: String,
    val version: Int,
    val target: RuleTarget,
    val steps: List<RuleStep>,
)

@Serializable
internal data class RuleTarget(val packageName: String, val testedAppVersion: String)

@Serializable
internal data class RuleSelector(val text: String, val resourceId: String? = null)

@Serializable
internal sealed interface RuleStep {
    @Serializable @SerialName("LAUNCH")
    data class Launch(val activity: String) : RuleStep

    @Serializable @SerialName("WAIT_PAGE")
    data class WaitPage(val requiredTexts: List<String>, val timeoutMs: Long) : RuleStep

    @Serializable @SerialName("CLICK_NODE")
    data class ClickNode(val selector: RuleSelector, val timeoutMs: Long) : RuleStep

    @Serializable @SerialName("VERIFY_EMPTY")
    data class VerifyEmpty(val title: String, val emptyText: String, val timeoutMs: Long) : RuleStep
}

internal const val CAINIAO_HOME = "com.cainiao.wireless.homepage.view.activity.HomePageActivity"
internal val ruleJson = Json { classDiscriminator = "action"; prettyPrint = true }

/** The only JSON-to-execution boundary. The runner accepts this type, not arbitrary JSON. */
internal class ValidatedRule private constructor(val template: RuleTemplate) {
    fun encode(): String = ruleJson.encodeToString(RuleTemplate.serializer(), template)

    companion object {
        fun parse(raw: String): ValidatedRule {
            val rule = ruleJson.decodeFromString(RuleTemplate.serializer(), raw)
            require(rule.schemaVersion == 1) { "不支持的 schemaVersion：${rule.schemaVersion}" }
            require(rule.ruleId.isNotBlank() && rule.version > 0) { "ruleId 不能为空，version 必须为正整数" }
            require(rule.target.packageName == CAINIAO_PACKAGE) { "本轮只允许菜鸟包名 $CAINIAO_PACKAGE" }
            require(rule.target.testedAppVersion.isNotBlank()) { "必须声明已测试的 App 版本" }
            require(rule.steps.size == 4 && rule.steps[0] is RuleStep.Launch &&
                rule.steps[1] is RuleStep.WaitPage && rule.steps[2] is RuleStep.ClickNode &&
                rule.steps[3] is RuleStep.VerifyEmpty) { "本版步骤顺序必须为 LAUNCH → WAIT_PAGE → CLICK_NODE → VERIFY_EMPTY" }
            for (step in rule.steps) when (step) {
                is RuleStep.Launch -> require(step.activity == CAINIAO_HOME) { "只允许已验证的菜鸟首页入口" }
                is RuleStep.WaitPage -> {
                    require(step.requiredTexts.isNotEmpty() && step.requiredTexts.all { it.isNotBlank() }) { "等待条件不能为空" }
                    require(step.timeoutMs in 100..10_000) { "等待时间必须为 100～10000 ms" }
                }
                is RuleStep.ClickNode -> {
                    require(step.selector.text.isNotBlank()) { "点击节点文字不能为空" }
                    require(step.selector.resourceId == null || step.selector.resourceId.isNotBlank()) { "resourceId 不能是空字符串" }
                    require(step.timeoutMs in 100..10_000) { "点击等待时间必须为 100～10000 ms" }
                }
                is RuleStep.VerifyEmpty -> {
                    require(step.title.isNotBlank() && step.emptyText.isNotBlank() && step.title != step.emptyText) { "页面标题与空状态断言必须非空且不同" }
                    require(step.timeoutMs in 100..10_000) { "核验等待时间必须为 100～10000 ms" }
                }
            }
            return ValidatedRule(rule)
        }
    }
}
