package com.life0423.carddock.ui

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

/** Deliberately static UI fixtures: no compiler, device, disk, clock or network. */
internal object UiFixture {
    private const val fetchedAt = 1_794_218_400_000L

    val express = template(
        "express", "待取快递", TemplateKind.EXPRESS,
        "取件信息集中展示，出门前看一眼就够了。", "把我的待取快递放到空间",
    )
    val delivery = template(
        "delivery", "配送进度", TemplateKind.DELIVERY,
        "追踪一份配送的当前状态与预计到达时间。", "查看我的配送进度",
    )
    val travel = template(
        "travel", "下一程出行", TemplateKind.TRAVEL,
        "把下一程的车次、出发时间与检票口放在一起。", "把下一程出行放到空间",
    )
    val templates = listOf(express, delivery, travel)
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
        rule = express.rule,
        snapshot = snapshot,
        run = RunInfo("run-express", RunPhase.SUCCEEDED),
    )
    val state = PrototypeState(
        loading = false,
        cards = listOf(
            card,
            CardRecord(
                id = "delivery-card",
                title = "午餐配送",
                kind = TemplateKind.DELIVERY,
                rule = delivery.rule,
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

    private fun template(
        id: String,
        title: String,
        kind: TemplateKind,
        description: String,
        prompt: String,
    ) = TemplateDefinition(
        id = id,
        title = title,
        description = description,
        kind = kind,
        examplePrompt = prompt,
        rule = RuleSpec(
            id = "rule-$id",
            sourceId = "demo-$id",
            targetKey = "sample-$id",
            steps = listOf(
                RuleStep.AssertTarget("sample-$id"),
                RuleStep.ReadField("status", "status", "状态"),
                RuleStep.RequireField("status"),
            ),
        ),
    )
}
