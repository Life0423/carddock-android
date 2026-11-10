package com.life0423.carddock.core.testing

import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.model.CardSnapshot
import com.life0423.carddock.core.model.FieldValue
import com.life0423.carddock.core.model.RunInfo
import com.life0423.carddock.core.model.RunPhase
import com.life0423.carddock.core.model.TemplateDefinition
import com.life0423.carddock.core.model.TemplateKind
import com.life0423.carddock.core.rule.RuleSpec
import com.life0423.carddock.core.rule.RuleStep
import com.life0423.carddock.core.session.PrototypeActions
import com.life0423.carddock.core.session.PrototypeState

/**
 * 界面测试的静态状态，不启动编译器、设备或磁盘。
 *
 * 模板列表由调用方传入。生产目录在 app 的 TemplateCatalog，这里不再抄一份标题和文案。
 * [card] 只负责空间页的展示样本，它的规则不会被执行。
 */
object UiFixture {
    private const val fetchedAt = 1_794_218_400_000L

    val snapshot = CardSnapshot(
        fields = listOf(
            FieldValue("code", "取件码", "8-26-1088"),
            FieldValue("station", "取件地点", "社区服务站 · A 区"),
            FieldValue("count", "待取数量", "2 件包裹"),
        ),
        fetchedAt = fetchedAt,
    )
    val card = CardRecord(
        id = "express-card",
        title = "我的待取快递",
        kind = TemplateKind.EXPRESS,
        rule = displayRule("express-card"),
        snapshot = snapshot,
        run = RunInfo("run-express", RunPhase.SUCCEEDED),
    )

    /** 用调用方给出的模板拼一屏可点击的空间和模板页。templates 原样放进状态，不在这里改标题。 */
    fun state(templates: List<TemplateDefinition>) = PrototypeState(
        loading = false,
        cards = listOf(
            card,
            CardRecord(
                id = "delivery-card",
                title = "午餐配送",
                kind = TemplateKind.DELIVERY,
                rule = displayRule("delivery-card"),
                snapshot = CardSnapshot(
                    listOf(
                        FieldValue("status", "当前状态", "配送途中"),
                        FieldValue("eta", "预计到达", "12:35 前"),
                    ),
                    fetchedAt,
                ),
            ),
        ),
        templates = templates,
    )

    /** 把界面回调记成事件名，测试只断言用户点了什么。 */
    fun actions(events: MutableList<String>) = PrototypeActions(
        openCreator = { events += "open:${it?.id ?: "blank"}" },
        closeCreator = { events += "close" },
        changePrompt = { events += "prompt:$it" },
        changeTitle = { events += "title:$it" },
        trialDraft = { events += "trial" },
        saveDraft = { events += "save" },
        refreshCard = { events += "refresh:$it" },
        cancelCard = { events += "cancel:$it" },
        resumeCard = { events += "resume:$it" },
        deleteCard = { events += "delete:$it" },
        changeScenario = { events += "scenario:${it.name}" },
        resetDemo = { events += "reset" },
        dismissNotice = { events += "notice" },
    )

    private fun displayRule(id: String) = RuleSpec(
        id = "rule-$id",
        sourceId = "demo-$id",
        targetKey = "sample-$id",
        steps = listOf(
            RuleStep.AssertTarget("sample-$id"),
            RuleStep.ReadField("status", "status", "状态"),
            RuleStep.RequireField("status"),
        ),
    )
}
