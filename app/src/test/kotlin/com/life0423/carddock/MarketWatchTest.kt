package com.life0423.carddock

import android.app.Application
import android.app.NotificationManager
import android.app.job.JobScheduler
import android.content.Context
import com.life0423.carddock.ai.PlanAnswer
import com.life0423.carddock.ai.proposalFrom
import com.life0423.carddock.core.model.*
import com.life0423.carddock.core.port.DeviceResponse
import com.life0423.carddock.core.port.DiscoveryResult
import com.life0423.carddock.platform.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 数据固定用于验证来源边界、已保存跟踪卡与通知。价格变动由测试输入构造。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MarketWatchTest {
    private val page = """<script type="application/json" id="metrics">{"data":{"currency":"CNY","products":[{"partNumber":"MFEX4CH/A","category":"display","name":"Studio Display - Standard glass - Tilt-adjustable stand","price":{"fullPrice":11999.0}}]}}</script>"""

    @Test fun verifiesCurrencyFamilyAndPriceBeforeRanking() {
        val offers = parseDisplayOffers(page, "studio-display")
        val catalog = DisplayCatalog(offers, 123)
        assertTrue("¥11999" in (catalog.snapshot(PRICE_RANK) as DeviceResponse.Ready).snapshot.fields.getValue("ranking"))
        assertTrue(catalog.snapshot("missing") is DeviceResponse.NeedsUser)
        for (invalid in listOf(page.replace("CNY","USD"), page.replace("11999.0","0.0"), page.replace("Studio Display -","Studio Display XDR -"))) {
            assertThrows(IllegalArgumentException::class.java) { parseDisplayOffers(invalid, "studio-display") }
        }
        val selected = proposalFrom(PlanAnswer("market_watch", partNumber = "MFEX4CH/A"), emptyList(), offers) as DiscoveryResult.Plans
        assertEquals(offers.single().part, selected.choices.single().rule.targetKey)
        assertTrue(proposalFrom(PlanAnswer("market_watch", budgetYuan = 8000), emptyList(), offers) is DiscoveryResult.Incomplete)
    }

    @Test fun savedWatchSchedulesAndNotifiesOnlyOnFreshComparableDrop() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        val watch = MarketWatch(ctx)
        val offer = parseDisplayOffers(page, "studio-display").single()
        val template = watchTemplate(offer)
        fun card(at: Long, yuan: Int) = CardRecord("watch", template.title, template.kind, template.rule,
            snapshot = CardSnapshot(listOf(FieldValue("price", "官网标价", "¥$yuan")), at, PRICE_BASIS))
        val scheduler = ctx.getSystemService(JobScheduler::class.java)
        val notices = shadowOf(ctx.getSystemService(NotificationManager::class.java))
        watch.sync(listOf(card(10, 12000)))
        assertNotNull(scheduler.getPendingJob(WATCH_JOB))
        assertEquals(0, notices.size())
        watch.sync(listOf(card(20, 11999)))
        assertEquals(1, notices.size())
        assertTrue(watch.state.value.getValue(offer.part).backgroundChecks.isEmpty())
        watch.recordBackground(card(20, 11999))
        watch.recordBackground(card(20, 11999))
        watch.sync(listOf(card(18, 10000)))
        watch.sync(listOf(card(21, 11999)))
        assertEquals(listOf(12000,11999), watch.state.value.getValue(offer.part).changes.map { it.yuan })
        assertEquals(21L, MarketWatch(ctx).state.value.getValue(offer.part).checkedAt)
        assertEquals(listOf(20L), MarketWatch(ctx).state.value.getValue(offer.part).backgroundChecks)
        watch.sync(emptyList())
        assertNull(scheduler.getPendingJob(WATCH_JOB))
        assertTrue(watch.state.value.isEmpty())
        assertEquals(0, notices.size())
    }
}
