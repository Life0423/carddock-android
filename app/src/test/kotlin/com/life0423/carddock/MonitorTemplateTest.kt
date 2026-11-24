package com.life0423.carddock

import com.life0423.carddock.ai.PlanAnswer
import com.life0423.carddock.ai.compileCainiaoCandidate
import com.life0423.carddock.ai.proposalFrom
import com.life0423.carddock.core.model.DisplayField
import com.life0423.carddock.core.model.TemplateDefinition
import com.life0423.carddock.core.port.*
import com.life0423.carddock.core.rule.*
import com.life0423.carddock.platform.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** 验证需求候选、持久化参数、按 SKU 取证及已有菜鸟模型边界。 */
class MonitorTemplateTest {
    @Test fun unresolvedSourceOrTriggerNeverProducesExecutablePlans() {
        val need = proposalFrom(PlanAnswer("clarify", missing = "trigger"), emptyList()) as DiscoveryResult.Incomplete
        assertTrue(need.reason.contains("触发条件"))
        val unsupported = proposalFrom(PlanAnswer("unsupported", missing = "source"), emptyList()) as DiscoveryResult.Incomplete
        assertTrue(unsupported.reason.contains("数据来源"))
        assertTrue(proposalFrom(PlanAnswer("market_rank", missing = "object"), emptyList()) is DiscoveryResult.Incomplete)
    }
    @Test fun vagueRequestOffersUsesAndBudgetSurvivesSaveAndEdit() {
        val proposal = proposalFrom(PlanAnswer("monitor_compare", budgetYuan = 4500), emptyList()) as DiscoveryResult.Plans
        assertEquals(MonitorUse.entries, proposal.choices.map { it.rule.monitorComparison!!.use })
        val original = proposal.choices.first().copy(title = "书桌选购", description = "我的比较标准",
            display = listOf(DisplayField("rog", "对照款"), DisplayField("advice", "我的建议")))
        val saved = Json.decodeFromString<TemplateDefinition>(Json.encodeToString(original))
        assertEquals(4500, saved.rule.monitorComparison!!.budgetYuan)
        val game = updateMonitorPlan(saved, MonitorComparison(MonitorUse.GAME, 6000))
        assertEquals(saved.title, game.title)
        assertEquals(saved.description, game.description)
        assertEquals(saved.display, game.display)
        assertEquals(MonitorComparison(MonitorUse.GAME, 6000), game.rule.monitorComparison)
        assertEquals(MonitorUse.OFFICE, saved.rule.monitorComparison!!.use)
    }

    @Test fun unsupportedRequirementsDoNotProduceRunnablePlan() {
        for (answer in listOf(PlanAnswer("watch_unavailable"), PlanAnswer("unknown"),
            PlanAnswer("monitor_compare", budgetYuan = -1), PlanAnswer("monitor_price", MonitorUse.GAME))) {
            assertTrue(proposalFrom(answer, emptyList()) is DiscoveryResult.Incomplete)
        }
        assertEquals(jdTemplates, (proposalFrom(PlanAnswer("monitor_price"), emptyList()) as DiscoveryResult.Plans).choices)
    }

    @Test fun recommendationReferencesOnlyFactsAvailableForThatSkuAndPurpose() {
        val sources = JdProduct.entries.map { DeviceSnapshot(JD_PACKAGE, it.sku,
            mapOf("title" to it.titlePrefix + " 27英寸", "variant" to it.variant), "公开页") }
        val plan = MonitorComparison(MonitorUse.OFFICE, 4000)
        val snapshot = (comparisonSnapshot(plan, sources, PairAdvice(1, 1, 2)) as DeviceResponse.Ready).snapshot
        assertTrue("先试华硕" in snapshot.fields.getValue("advice"))
        assertTrue("27英寸" in snapshot.fields.getValue("hisense"))
        assertTrue("4K" in snapshot.fields.getValue("rog"))
        assertTrue("待核价" in snapshot.fields.getValue("check"))
        assertTrue(comparisonSnapshot(plan, sources, PairAdvice(2, 1, 2)) is DeviceResponse.NeedsUser)
        assertTrue(comparisonSnapshot(plan, sources, PairAdvice(1, 1, 3)) is DeviceResponse.NeedsUser)
        val missing = sources[0].copy(fields = mapOf("title" to "海信", "variant" to "黑曜屏"))
        assertTrue(comparisonSnapshot(plan, listOf(missing, sources[1]), PairAdvice(1, 1, 0)) is DeviceResponse.NeedsUser)
    }

    @Test fun competitivePreferencePersistsAndUsesComparableRefreshRateEvidence() {
        val proposal = proposalFrom(PlanAnswer("monitor_compare", MonitorUse.GAME, focus = MonitorFocus.MOTION),
            emptyList()) as DiscoveryResult.Plans
        val saved = Json.decodeFromString<TemplateDefinition>(Json.encodeToString(proposal.choices.single()))
        val sources = JdProduct.entries.map { DeviceSnapshot(JD_PACKAGE, it.sku,
            mapOf("title" to it.titlePrefix + " 27英寸", "variant" to it.variant)) }
        val fields = (comparisonSnapshot(saved.rule.monitorComparison!!, sources, PairAdvice(1, 1, 2)) as DeviceResponse.Ready).snapshot.fields
        assertTrue("240Hz" in fields.getValue("rog"))
        assertTrue("补查" in fields.getValue("advice"))
        assertFalse("OLED" in fields.getValue("advice"))
        assertTrue(proposalFrom(PlanAnswer("monitor_compare", MonitorUse.OFFICE, focus = MonitorFocus.MOTION),
            emptyList()) is DiscoveryResult.Incomplete)
    }

    @Test fun cainiaoCandidateRejectsComparisonOptions() {
        val rule = cainiaoEmptyTemplate.rule.copy(monitorComparison = MonitorComparison(MonitorUse.OFFICE))
        assertTrue(compileCainiaoCandidate(Json.encodeToString(rule)) is CompileResult.Unsupported)
    }
}
