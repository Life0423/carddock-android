package com.wangzan101.mika.experiment

import com.wangzan101.mika.workspace.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class WorkspaceContractTest {
    private val old = WorkspaceCard("a", "菜鸟待取", "rule", 1, "菜鸟", "暂无待取包裹", 100, 100, CardStatus.FRESH_VALID, "成功")
    @Test fun supportedRequestsMatchOnlyFiniteTemplate() {
        MockCompiler.accepted.forEach { assertTrue(MockCompiler.supports(it)) }
        assertTrue(MockCompiler.supports("  查看菜鸟待取包裹。  "))
    }
    @Test fun unsupportedOrQualifiedRequestsCannotSilentlyBecomeDefaultRule() {
        listOf("", "帮我寄件", "查看美团订单", "查看菜鸟待取包裹只看学校站", "查询我的菜鸟待取状态并支付", "查看菜鸟物流").forEach {
            assertFalse(it, MockCompiler.supports(it))
        }
    }
    @Test fun everyFailurePreservesExactLastSuccessfulSnapshotAndTime() {
        listOf("TIMEOUT", "CANCELLED", "UNAVAILABLE", "UNCONFIRMED", "ERROR", "NEED_HANDOFF", "APP_VERSION_MISMATCH", "INVALID_RULE").forEach {
            val next = old.afterAttempt(it, "本次失败", "误传入的新值", 200)
            assertEquals(old.lastValidText, next.lastValidText)
            assertEquals(old.lastSuccessAt, next.lastSuccessAt)
            assertEquals(200L, next.lastAttemptAt)
            assertEquals("本次失败", next.reason)
        }
    }
    @Test fun successReplacesSnapshotAndTimestamp() {
        val next = old.afterAttempt("EMPTY_CONFIRMED", "本次核验", "页面实际空状态", 200)
        assertEquals("页面实际空状态", next.lastValidText)
        assertEquals(200L, next.lastSuccessAt)
        assertEquals(CardStatus.FRESH_VALID, next.status)
    }
    @Test fun jdWrongIdentityPreservesStructuredSnapshot() {
        val jd = old.copy(snapshotJson = "{\"sku\":\"$JD_A_SKU\"}")
        val failed = jd.afterAttempt("IDENTITY_MISMATCH", "规格不符", "wrong", 200, "wrong")
        assertEquals(jd.lastSuccessAt, failed.lastSuccessAt)
        assertEquals(jd.snapshotJson, failed.snapshotJson)
        assertEquals(CardStatus.STALE_CACHED, failed.status)
    }
    @Test fun modelCandidateUsesFrozenCainiaoActionsAndSelectors() {
        val json = File("src/debug/assets/rules/cainiao-empty-v1.json").readText()
            .replace("cainiao-pickup-empty", CAINIAO_MODEL_RULE)
        val expected = ValidatedRule.parse(json).template
        assertNotNull(validateCainiaoModelCandidate(json, expected))
        assertNull(validateCainiaoModelCandidate(json.replace("暂无待取包裹", "首页"), expected))
        assertNull(validateCainiaoModelCandidate(json.replace("取包裹", "寄包裹"), expected))
        assertNull(validateCainiaoModelCandidate("{}", expected))
    }
    @Test fun genericTimeoutDoesNotInventRuleDamage() {
        assertEquals(CardStatus.STALE_CACHED, old.afterAttempt("TIMEOUT", "超时", "", 200).status)
    }
    @Test fun loginRequestsHandoffAndVersionRequestsRuleReview() {
        assertEquals(CardStatus.NEED_HANDOFF, old.afterAttempt("NEED_HANDOFF", "登录", "", 200).status)
        assertEquals(CardStatus.NEED_HEAL, old.afterAttempt("APP_VERSION_MISMATCH", "版本变化", "", 200).status)
    }
}
