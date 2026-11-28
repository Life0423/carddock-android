package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.userscreen.UserScreenMessage
import com.wangzan101.mika.core.observation.Place
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.replay.Anchors
import com.wangzan101.mika.core.verify.AnswerSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnGateTest {
    private val unreviewed = TaskState.Succeeded("ok", verified = false, reviewed = false)

    @Test fun excludedAppCannotBecomeTheSourceJustBecauseItsNameAppearsInTheRequest() {
        val apps = listOf("shop" to "淘宝", "parcel" to "菜鸟")
        val request = "只在手机淘宝里查待取包裹。不要跳去独立菜鸟 App，只浏览。"
        assertTrue(LearnGate.matchesPickupSource(request, "shop", apps))
        assertFalse(LearnGate.matchesPickupSource(request, "parcel", apps))
        assertFalse(LearnGate.matchesPickupSource("只在淘宝查待取件，别跳到菜鸟", "parcel", apps))
        assertFalse(LearnGate.matchesPickupSource(request, "parcel", emptyList()))
        assertFalse(LearnGate.matchesPickupSource("不用菜鸟查", "parcel", apps))
        assertTrue(LearnGate.matchesPickupSource("不用菜鸟查", "shop", apps))
        assertTrue(LearnGate.matchesPickupSource("在淘宝查物流，不要下单", "shop", apps))
    }

    @Test fun doneWithoutReviewCounts() {
        assertTrue(LearnGate.done(unreviewed, RunOutcome.Done))
        assertTrue(LearnGate.done(TaskState.Succeeded("ok", verified = true, reviewed = true), RunOutcome.Done))
    }

    @Test fun reviewedButNotVerifiedIsBlocked() {
        assertFalse(LearnGate.done(TaskState.Succeeded("ok", verified = false, reviewed = true, note = "x"), RunOutcome.Done))
        assertFalse(LearnGate.done(TaskState.Succeeded("ok", verified = false, unconfirmed = listOf("3 元"), reviewed = false), RunOutcome.Done))
    }

    @Test fun pickupOutcomeUsesCurrentTerminalSourceForEmptyAndPresentResults() {
        val pkg = "com.parcel"
        val goal = "查看取件码"
        val empty = "暂无待取包裹"
        val source = AnswerSource(2, "read", pkg, null, 2, source = "phone")
        val end = TaskState.Succeeded("当前页面显示「$empty」，本次无取件码。", sources = listOf(source))
        val anchors = Anchors(pkg, listOf("包裹"))
        val write = RunEvent.ActionTarget(1, 1, "phone_tap", "{}", null, anchors, anchors, true)
        val read = RunEvent.Observed(2, 2, "我的包裹\n$empty", 2, source = "phone", place = Place.app(pkg), inputs = emptyList())
        fun verified(vararg e: RunEvent) = LearnGate.verifiedEmptyPickup(goal, end, listOf(write) + e, pkg)
        assertTrue(verified(read))
        val icon = read.copy(id = 3, text = "截图核实入口。点击后出现「$empty」。", kind = "fact", inputs = null,
            untrusted = true, verifiedVisualAction = true)
        assertTrue(verified(read, icon))
        assertFalse(verified(icon))
        assertFalse(verified(read.copy(untrusted = true)))
        assertFalse(verified(read.copy(id = 3)))
        assertFalse(verified(read.copy(inputs = listOf(empty), focused = empty)))
        assertFalse(verified(read.copy(place = Place.app("com.unrelated"))))
        assertFalse(verified(read, read.copy(text = "请先登录")))
        assertFalse(verified(read.copy(text = "$empty\n加载中")))
        assertFalse(verified(read, write.copy(atMs = 3, step = 3)))
        val present = read.copy(text = "我的包裹\n取件码：A2-78-904")
        assertFalse(verified(present))
        val answer = end.copy(answer = "取件码：A2-78-904")
        assertTrue(LearnGate.verifiedPickupCodes(answer, listOf(write, present), pkg))
        assertFalse(LearnGate.verifiedPickupCodes(answer, listOf(write, read), pkg))
        assertFalse(LearnGate.verifiedPickupCodes(answer.copy(answer = "取件码：A2-78-905"), listOf(write, present), pkg))
        val digits = listOf(write, read.copy(text = "取件码：1234"))
        assertFalse(LearnGate.verifiedPickupCodes(answer.copy(answer = "领取号码12345"), digits, pkg))
        assertTrue(LearnGate.verifiedPickupCodes(answer.copy(answer = "领取号码1234"), digits, pkg))
    }

    /** 旧回执即便保存了成功判定与全程回放，仍须能重建指定来源的空态或实际码值。 */
    @Test fun recoveredPickupRequiresVisibleCodesOrEmptyStateInTheAcceptedApp() {
        val pkg = "test.parcel"
        val apps = listOf(pkg to "菜鸟", "test.store" to "淘宝")
        val anchors = Anchors(pkg, listOf("包裹"))
        val source = AnswerSource(3, "read", pkg, null, 2, source = "phone")
        val end = TaskState.Succeeded("取件码：C1-222", sources = listOf(source))
        fun proof(scope: String, answer: TaskState.Succeeded, page: String): Boolean {
            val events = listOf(
                RunEvent.ActionTarget(1, 1, "phone_tap", "{}", null, anchors, anchors, true),
                RunEvent.Observed(2, 2, page, 3, source = "phone", place = Place.app(pkg), inputs = emptyList()),
                RunEvent.Replay(3, "test-route", 1, 1, true, ""),
                RunEvent.OutcomeJudged(4, "done"),
            )
            return LearnGate.verifiedPickupResult("查看取件码", scope, answer, events, apps)
        }
        assertFalse(proof("查看菜鸟", end, "取件码：***"))
        assertFalse(proof("查看淘宝", end, end.answer))
        assertTrue(proof("查看菜鸟", end, end.answer))
        val empty = end.copy(answer = "暂无待取包裹")
        assertFalse(proof("查看菜鸟", empty, "登录后查看"))
        assertFalse(proof("查看淘宝", empty, empty.answer))
        assertTrue(proof("查看菜鸟", empty, empty.answer))
    }

    @Test fun onlyDoneCounts() {
        for (o in listOf(RunOutcome.Partial, RunOutcome.NotPossible, RunOutcome.Unknown)) assertFalse(o.wire, LearnGate.done(unreviewed, o))
        assertFalse(LearnGate.done(TaskState.Failed("x", FailKind.GaveUp), RunOutcome.Done))
        assertFalse(LearnGate.done(TaskState.Cancelled, RunOutcome.Done))
    }

    @Test fun routeIsSavedOnlyForDoneRunsWithRoutesOn() {
        assertTrue(LearnGate.shouldSaveRoute(true, unreviewed, RunOutcome.Done, "r1", "打开设置"))
        assertFalse(LearnGate.shouldSaveRoute(true, unreviewed, RunOutcome.Partial, "r1", "打开设置"))
        assertFalse(LearnGate.shouldSaveRoute(true, unreviewed, RunOutcome.Unknown, "r1", "打开设置"))
        assertFalse(LearnGate.shouldSaveRoute(true, TaskState.Succeeded("ok", verified = false, reviewed = true, note = "x"), RunOutcome.Done, "r1", "打开设置"))
        assertFalse(LearnGate.shouldSaveRoute(false, unreviewed, RunOutcome.Done, "r1", "打开设置"))
        assertFalse(LearnGate.shouldSaveRoute(true, unreviewed, RunOutcome.Done, null, "打开设置"))
        // 带着用户屏幕的话:意思在屏幕上,不按字记路线
        assertFalse(LearnGate.shouldSaveRoute(true, unreviewed, RunOutcome.Done, "r1", UserScreenMessage.compose("s1", "这个怎么关")))
    }
}
