package com.wangzan101.mika.experiment

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

internal data class RulePage(val packageName: String?, val labels: Set<String>, val password: Boolean, val nodeCount: Int,
                             val detail: ProbeSnapshot? = null, val cainiaoHomeSelected: Boolean = false)
internal enum class RuleClick { ACCEPTED, NOT_FOUND, AMBIGUOUS, REJECTED }
internal data class RuleResult(val outcome: String, val message: String, val verifiedTitle: String = "", val verifiedEmpty: String = "",
                               val orderFields: MeituanOrderFields? = null, val jdFields: JdShareEvidence? = null) {
    val successful: Boolean get() = outcome == "EMPTY_CONFIRMED" || orderFields != null || jdFields != null
    val displayText: String get() = jdFields?.let {
        val price = it.appPrice?.let { p ->
            val time = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(p.verifiedAt))
            "App 上次核验 ¥${p.amount} · ${p.priceLabel}\n${p.visibleDiscount ?: "优惠条件未核验"} · $time"
        } ?: "价格未核验"
        "${it.title}\nSKU ${it.sku} · ${it.variant}\n$price"
    }
        ?: orderFields?.let { listOfNotNull(it.status, it.orderTime).joinToString(" · ") } ?: verifiedEmpty
}

internal interface RulePhone {
    fun installedVersion(packageName: String): String?
    fun launch(target: RuleTarget, activity: String)
    fun openMeituanOrder(order: MeituanOrderTarget)
    suspend fun retryMeituanOrderFromHome(order: MeituanOrderTarget)
    fun read(detailTarget: RuleTarget? = null): RulePage
    fun click(selector: RuleSelector): RuleClick
}

/** 执行已校验的有限规则步骤，执行过程不调用模型。 */
internal class LocalRuleRunner(private val phone: RulePhone) {
    suspend fun execute(
        validated: ValidatedRule,
        progress: (Int, String) -> Unit,
        observed: (RulePage) -> Unit,
        clicked: () -> Unit,
        order: MeituanOrderTarget? = null,
    ): RuleResult = withTimeout(30_000) run@{
        val rule = validated.template
        val version = phone.installedVersion(rule.target.packageName)
            ?: return@run RuleResult("UNAVAILABLE", "未安装规则对应的应用。")
        if (version != rule.target.testedAppVersion) {
            return@run RuleResult("APP_VERSION_MISMATCH", "已安装版本 $version 与规则已测试版本 ${rule.target.testedAppVersion} 不符，需要重新验证。")
        }
        for ((index, step) in rule.steps.withIndex()) {
            progress(index + 1, when (step) {
                is RuleStep.Launch -> "启动规则指定的首页"
                is RuleStep.WaitPage -> "等待：${step.requiredTexts.joinToString("、")}"
                is RuleStep.ClickNode -> "定位并点击：${step.selector.text}"
                is RuleStep.VerifyEmpty -> "核验：${step.title} / ${step.emptyText}"
                RuleStep.OpenMeituanOrder -> "重开已配置的美团订单"
                is RuleStep.VerifyMeituanOrder -> "核验同一订单的号码与状态"
                RuleStep.VerifyJdShare -> error("京东分享核验由手动入口执行")
            })
            if (step is RuleStep.Launch) {
                phone.launch(rule.target, step.activity)
                continue
            }
            if (step == RuleStep.OpenMeituanOrder) {
                phone.openMeituanOrder(order ?: return@run RuleResult("INVALID_RULE", "缺少本地订单配置。"))
                continue
            }
            val timeoutMs = when (step) {
                is RuleStep.WaitPage -> step.timeoutMs
                is RuleStep.ClickNode -> step.timeoutMs
                is RuleStep.VerifyEmpty -> step.timeoutMs
                is RuleStep.VerifyMeituanOrder -> step.timeoutMs
                else -> error("launch was already executed")
            }
            val stepResult = withTimeout(timeoutMs) stepWait@{
                var expanded = false
                var retriedOrderRoute = false
                var meituanHomeReads = 0
                while (true) {
                    delay(250)
                    val page = phone.read(if (step is RuleStep.VerifyMeituanOrder) rule.target else null)
                    observed(page)
                    if (step is RuleStep.VerifyMeituanOrder && page.packageName == "com.vivo.appfilter") {
                        return@stepWait RuleResult("NEED_HANDOFF", "请在手机上处理跨应用跳转提示，再重新核验。")
                    }
                    if (page.packageName != rule.target.packageName) {
                        meituanHomeReads = 0
                        continue
                    }
                    if (page.password || classifyCainiaoPage(page.packageName, page.labels, page.password) == CainiaoPage.AUTH ||
                        step is RuleStep.VerifyMeituanOrder && page.labels.any { it == "手机号登录" || it == "验证码登录" }) {
                        return@stepWait RuleResult("NEED_HANDOFF", "检测到登录或验证页面，请手动完成后重新试运行。")
                    }
                    when (step) {
                        is RuleStep.WaitPage -> if (step.requiredTexts.all { label ->
                                label in page.labels || label == "首页" && page.cainiaoHomeSelected
                            }) return@stepWait null
                        is RuleStep.ClickNode -> when (phone.click(step.selector)) {
                            RuleClick.NOT_FOUND -> Unit
                            RuleClick.AMBIGUOUS -> return@stepWait RuleResult("UNCONFIRMED", "选择器匹配多个可点击节点，已停止操作。")
                            RuleClick.REJECTED -> return@stepWait RuleResult("UNCONFIRMED", "目标节点未接受点击。")
                            RuleClick.ACCEPTED -> { clicked(); return@stepWait null }
                        }
                        is RuleStep.VerifyEmpty -> if (step.title in page.labels && step.emptyText in page.labels) {
                            return@stepWait RuleResult("EMPTY_CONFIRMED", "已核验：${step.emptyText}。", step.title, step.emptyText)
                        }
                        is RuleStep.VerifyMeituanOrder -> {
                            val detail = page.detail ?: continue
                            val targetOrder = checkNotNull(order)
                            if (!retriedOrderRoute) {
                                // 真机上出现过美团收到路由后仍停在首页。
                                meituanHomeReads = if (detail.hasMeituanHome()) meituanHomeReads + 1 else 0
                                if (meituanHomeReads == 8) {
                                    retriedOrderRoute = true
                                    phone.retryMeituanOrderFromHome(targetOrder)
                                    continue
                                }
                            }
                            val fields = detail.verifyMeituanOrder(targetOrder.orderNumber)
                            if (fields != null) return@stepWait RuleResult("ORDER_CONFIRMED",
                                "订单身份和状态已核验。", orderFields = fields)
                            if (!expanded && detail.hasCollapsedMeituanOrder()) {
                                // 详情刚打开时，展开控件可能还未就绪。
                                if (phone.click(RuleSelector("配送骑手/支付方式等")) == RuleClick.ACCEPTED) {
                                    expanded = true
                                    clicked()
                                }
                            }
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
