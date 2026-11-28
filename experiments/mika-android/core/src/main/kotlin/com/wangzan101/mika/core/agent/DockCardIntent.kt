package com.wangzan101.mika.core.agent

import kotlinx.serialization.Serializable

/** 卡坞保存触发和交付意图，Mika 只处理本轮现场动作。旧记录由宿主保留原有口径。 */
@Serializable
enum class DockCardIntent(val summary: String, val instruction: String) {
    Query("点卡查询并更新卡面", "执行原话中这次要查询或浏览的内容。"),
    IdentityPage("点卡打开官方身份码页", "进入指定应用的身份码页，成功条件是到达页面。"),
    IdentityDisplay("核实身份码展示状态", "进入身份码页，核实独立的出示或刷新提示。"),
    PickupPage("点卡打开官方待取件页", "进入选定来源中本人的待取件或取件详情页；明确的待取空态也有效。只报告到达状态，不转述码值。"),
    Price("点卡核价并更新卡面", "读取指定商品和规格的本次价格。"),
    DailyPrice("每天在后台核价并更新卡面", "现在只查一次指定商品和规格的实际价格；每日调用由卡坞负责。");

    val price get() = this == Price || this == DailyPrice
    val opensPage get() = this == IdentityPage || this == IdentityDisplay || this == PickupPage

    /** 用户原话另存；只移除句首指向卡坞的触发方式，保留目标应用内的操作和对象。 */
    fun currentAction(demand: String): String {
        val action = demand.replaceFirst(Regex("""^\s*(?:(?:我希望|我想|希望|请|当|以后|每次|用户|我)\s*)*(?:点击|点开|打开|点)(?:这张|这个|这款)(?:卡片|卡)(?:的时候|时候|时|之后|以后|后)?[，,：:\s]*"""), "")
        return if (price) action.replaceFirst(Regex("""^(?:请|帮我|我想)?(?:每天|每日|定时)?(?:跟踪|追踪|监测|监控)"""), "现在核对") else action
    }

    companion object {
        private val priceTerms = Regex("价格|标价|售价|多少钱|降价|比价|核价|领后价|券后价|会员价|到手价|补贴价|活动价|跟踪.{0,12}价|追踪.{0,12}价")
        fun interpret(goal: String): DockCardIntent = when {
            LearnGate.identityCodeQuery(goal) -> if (!LearnGate.identityArrivalOnly(goal) &&
                Regex("(核实|核对|验证|确认|检查).{0,12}(已展示|已显示|是否展示|是否显示|有效|可用|确实展示)").containsMatchIn(goal))
                IdentityDisplay else IdentityPage
            LearnGate.pickupCodeQuery(goal) || Regex("(打开|进入|跳到|跳转|显示|查看).*(待取件页|待取包裹页|取件详情|取件页)").containsMatchIn(goal) -> PickupPage
            Regex("分段|区间筛选|筛选|排行").containsMatchIn(goal) -> Query
            priceTerms.containsMatchIn(goal) &&
                Regex("跟踪|追踪|监控|监测|每天|每日|定时|降价提醒").containsMatchIn(goal) -> DailyPrice
            priceTerms.containsMatchIn(goal) -> Price
            else -> Query
        }
    }
}
