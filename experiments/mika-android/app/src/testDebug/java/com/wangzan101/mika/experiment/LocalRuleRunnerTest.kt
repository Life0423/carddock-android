package com.wangzan101.mika.experiment

import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalRuleRunnerTest {
    private val raw = File("src/debug/assets/rules/cainiao-empty-v1.json").readText()
    private class Phone : RulePhone {
        var version: String? = "8.11.1105"
        var launchCount = 0
        var clickCount = 0
        var didClick = false
        var packageName = CAINIAO_PACKAGE
        var auth = false
        var ambiguous = false
        var reject = false
        var extraEmptyText = ""
        var selected: RuleSelector? = null
        var homeLabels = setOf("首页", "取包裹")
        var homeIconSelected = false
        override fun installedVersion(packageName: String) = version
        override fun launch(target: RuleTarget, activity: String) { launchCount++ }
        override fun openMeituanOrder(order: MeituanOrderTarget) { launchCount++ }
        override suspend fun retryMeituanOrderFromHome(order: MeituanOrderTarget) = error("Unexpected retry")
        override fun read(detailTarget: RuleTarget?): RulePage = RulePage(packageName, when {
            auth -> setOf("登录", "请输入手机号")
            didClick -> setOf("取包裹", "暂无待取包裹", extraEmptyText)
            else -> homeLabels
        }, false, 10, cainiaoHomeSelected = !didClick && homeIconSelected)
        override fun click(selector: RuleSelector): RuleClick {
            clickCount++
            selected = selector
            return when {
                ambiguous -> RuleClick.AMBIGUOUS
                reject -> RuleClick.REJECTED
                selector.text != "取包裹" -> RuleClick.NOT_FOUND
                else -> { didClick = true; RuleClick.ACCEPTED }
            }
        }
    }
    private suspend fun execute(phone: Phone, text: String = raw): RuleResult =
        LocalRuleRunner(phone).execute(ValidatedRule.parse(text), { _, _ -> }, {}, {})

    @Test fun realRuleExecutesFourStepsAndOneClick() = runTest {
        val phone = Phone()
        val indices = mutableListOf<Int>()
        val result = LocalRuleRunner(phone).execute(ValidatedRule.parse(raw), { i, _ -> indices += i }, {}, {})
        assertEquals(listOf(1, 2, 3, 4), indices)
        assertEquals("EMPTY_CONFIRMED", result.outcome)
        assertEquals(1, phone.launchCount)
        assertEquals(1, phone.clickCount)
    }
    @Test fun appVersionMismatchStopsBeforeLaunch() = runTest {
        val phone = Phone().apply { version = "different" }
        assertEquals("APP_VERSION_MISMATCH", execute(phone).outcome)
        assertEquals(0, phone.launchCount)
    }
    @Test fun selectedHomeIconStillRequiresPickupEntry() = runTest {
        val phone = Phone().apply {
            homeLabels = setOf("取包裹")
            homeIconSelected = true
        }
        assertEquals("EMPTY_CONFIRMED", execute(phone).outcome)
        val missingPickup = Phone().apply {
            homeLabels = emptySet()
            homeIconSelected = true
        }
        val failure = runCatching { execute(missingPickup) }.exceptionOrNull()
        assertTrue(failure is TimeoutCancellationException)
        assertEquals(0, missingPickup.clickCount)
    }
    @Test fun vivoMeituanJumpPromptReturnsHandoffWithoutClicking() = runTest {
        val phone = Phone().apply {
            version = MEITUAN_VERSION
            packageName = "com.vivo.appfilter"
        }
        val rule = ValidatedRule.parse(File("src/debug/assets/rules/meituan-order-v1.json").readText())
        val result = LocalRuleRunner(phone).execute(rule, { _, _ -> }, {}, {},
            MeituanOrderTarget("1234567890123456789", "1234567890123456"))
        assertEquals("NEED_HANDOFF", result.outcome)
        assertEquals(1, phone.launchCount)
        assertEquals(0, phone.clickCount)
    }
    @Test fun missingAppStopsBeforeLaunch() = runTest {
        val phone = Phone().apply { version = null }
        assertEquals("UNAVAILABLE", execute(phone).outcome)
        assertEquals(0, phone.launchCount)
    }
    @Test fun waitingConditionComesFromJson() = runTest {
        val phone = Phone()
        val failure = runCatching { execute(phone, raw.replace("\"首页\", \"取包裹\"", "\"不存在的页面\"")) }.exceptionOrNull()
        assertTrue(failure is TimeoutCancellationException)
        assertEquals(0, phone.clickCount)
    }
    @Test fun missingSelectorDoesNotClickSomeOtherNode() = runTest {
        val phone = Phone()
        val failure = runCatching { execute(phone, raw.replace("\"text\": \"取包裹\"", "\"text\": \"不存在的入口\"")) }.exceptionOrNull()
        assertTrue(failure is TimeoutCancellationException)
        assertEquals("不存在的入口", phone.selected!!.text)
        assertFalse(phone.didClick)
    }
    @Test fun wrongAssertionDoesNotReturnEmpty() = runTest {
        assertTrue(runCatching { execute(Phone(), raw.replace("暂无待取包裹", "不存在的空状态")) }.exceptionOrNull() is TimeoutCancellationException)
    }
    @Test fun supportedAssertionIsReallyReadFromJson() = runTest {
        val phone = Phone().apply { extraEmptyText = "替代空状态" }
        val result = execute(phone, raw.replace("暂无待取包裹", "替代空状态"))
        assertEquals("替代空状态", result.verifiedEmpty)
    }
    @Test fun matchingLabelsFromAnotherPackageAreInsufficient() = runTest {
        assertTrue(runCatching { execute(Phone().apply { packageName = "other.app" }) }.exceptionOrNull() is TimeoutCancellationException)
    }
    @Test fun loginStopsBeforeClick() = runTest {
        val phone = Phone().apply { auth = true }
        assertEquals("NEED_HANDOFF", execute(phone).outcome)
        assertEquals(0, phone.clickCount)
    }
    @Test fun ambiguousSelectorStopsWithoutSuccess() = runTest {
        assertEquals("UNCONFIRMED", execute(Phone().apply { ambiguous = true }).outcome)
    }
    @Test fun rejectedClickStopsWithoutSuccess() = runTest {
        assertEquals("UNCONFIRMED", execute(Phone().apply { reject = true }).outcome)
    }
    @Test fun cancellationStopsFurtherOperations() = runTest {
        val phone = Phone().apply { packageName = "other.app" }
        val job = launch { execute(phone) }
        advanceTimeBy(300)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(0, phone.clickCount)
    }
}
