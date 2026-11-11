package com.wangzan101.mika.experiment

import com.wangzan101.mika.workspace.*
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
    @Test fun genericTimeoutDoesNotInventRuleDamage() {
        assertEquals(CardStatus.STALE_CACHED, old.afterAttempt("TIMEOUT", "超时", "", 200).status)
    }
    @Test fun loginRequestsHandoffAndVersionRequestsRuleReview() {
        assertEquals(CardStatus.NEED_HANDOFF, old.afterAttempt("NEED_HANDOFF", "登录", "", 200).status)
        assertEquals(CardStatus.NEED_HEAL, old.afterAttempt("APP_VERSION_MISMATCH", "版本变化", "", 200).status)
    }
}
