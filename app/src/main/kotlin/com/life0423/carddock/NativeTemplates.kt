package com.life0423.carddock

import com.life0423.carddock.core.model.TemplateDefinition
import com.life0423.carddock.core.model.TemplateKind
import com.life0423.carddock.core.rule.RuleSpec
import com.life0423.carddock.core.rule.RuleStep

/** 首个真机模板仅证明明确空状态，不把未发现取件码当作没有包裹。 */
val cainiaoEmptyTemplate = TemplateDefinition(
    id = "cainiao-empty", title = "菜鸟待取 · 真机", kind = TemplateKind.EXPRESS,
    description = "核验菜鸟取包裹页的明确空状态。先在我的中启用页面读取，试运行后 20 秒内手动切到目标页，再返回确认保存。",
    examplePrompt = "核验菜鸟暂无待取包裹",
    rule = RuleSpec(
        id = "cainiao-empty-v1", sourceId = "com.cainiao.wireless", targetKey = "cainiao.pickup.empty",
        steps = listOf(
            RuleStep.AssertTarget("cainiao.pickup.empty"),
            RuleStep.ReadField("status", "status", "待取状态"),
            RuleStep.RequireField("status"),
        ),
    ),
)
