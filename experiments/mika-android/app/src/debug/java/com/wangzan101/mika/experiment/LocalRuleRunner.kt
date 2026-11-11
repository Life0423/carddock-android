package com.wangzan101.mika.experiment

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

internal data class RulePage(val packageName: String?, val labels: Set<String>, val password: Boolean, val nodeCount: Int)
internal enum class RuleClick { ACCEPTED, NOT_FOUND, AMBIGUOUS, REJECTED }
internal data class RuleResult(val outcome: String, val message: String, val verifiedTitle: String = "", val verifiedEmpty: String = "")

internal interface RulePhone {
    fun installedVersion(packageName: String): String?
    fun launch(target: RuleTarget, activity: String)
    fun read(): RulePage
    fun click(selector: RuleSelector): RuleClick
}

/** Four restricted operations, with no model dependency and no JSON revalidation. */
internal class LocalRuleRunner(private val phone: RulePhone) {
    suspend fun execute(
        validated: ValidatedRule,
        progress: (Int, String) -> Unit,
        observed: (RulePage) -> Unit,
        clicked: () -> Unit,
    ): RuleResult = withTimeout(30_000) run@{
        val rule = validated.template
        val version = phone.installedVersion(rule.target.packageName)
            ?: return@run RuleResult("UNAVAILABLE", "未找到菜鸟应用。")
        if (version != rule.target.testedAppVersion) {
            return@run RuleResult("APP_VERSION_MISMATCH", "已安装版本 $version 与规则已测试版本 ${rule.target.testedAppVersion} 不符，需要重新验证。")
        }
        for ((index, step) in rule.steps.withIndex()) {
            progress(index + 1, when (step) {
                is RuleStep.Launch -> "启动规则指定的首页"
                is RuleStep.WaitPage -> "等待：${step.requiredTexts.joinToString("、")}"
                is RuleStep.ClickNode -> "定位并点击：${step.selector.text}"
                is RuleStep.VerifyEmpty -> "核验：${step.title} / ${step.emptyText}"
            })
            if (step is RuleStep.Launch) {
                phone.launch(rule.target, step.activity)
                continue
            }
            val timeoutMs = when (step) {
                is RuleStep.WaitPage -> step.timeoutMs
                is RuleStep.ClickNode -> step.timeoutMs
                is RuleStep.VerifyEmpty -> step.timeoutMs
                else -> error("launch was already executed")
            }
            val stepResult = withTimeout(timeoutMs) stepWait@{
                while (true) {
                    delay(250)
                    val page = phone.read()
                    observed(page)
                    if (page.packageName != rule.target.packageName) continue
                    if (classifyCainiaoPage(page.packageName, page.labels, page.password) == CainiaoPage.AUTH) {
                        return@stepWait RuleResult("NEED_HANDOFF", "检测到登录或验证页面，请手动完成后重新试运行。")
                    }
                    when (step) {
                        is RuleStep.WaitPage -> if (page.labels.containsAll(step.requiredTexts)) return@stepWait null
                        is RuleStep.ClickNode -> when (phone.click(step.selector)) {
                            RuleClick.NOT_FOUND -> Unit
                            RuleClick.AMBIGUOUS -> return@stepWait RuleResult("UNCONFIRMED", "选择器匹配多个可点击节点，已停止操作。")
                            RuleClick.REJECTED -> return@stepWait RuleResult("UNCONFIRMED", "目标节点未接受点击。")
                            RuleClick.ACCEPTED -> { clicked(); return@stepWait null }
                        }
                        is RuleStep.VerifyEmpty -> if (step.title in page.labels && step.emptyText in page.labels) {
                            return@stepWait RuleResult("EMPTY_CONFIRMED", "已核验：${step.emptyText}。", step.title, step.emptyText)
                        }
                        else -> Unit
                    }
                }
                @Suppress("UNREACHABLE_CODE") null
            }
            if (stepResult != null) return@run stepResult
        }
        RuleResult("UNCONFIRMED", "没有生成通过断言的结果。")
    }
}
