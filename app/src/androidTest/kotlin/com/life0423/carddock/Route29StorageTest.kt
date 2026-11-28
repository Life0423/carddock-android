package com.life0423.carddock

import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.core.replay.Route
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.replay.Anchors
import com.wangzan101.mika.core.replay.RouteStep
import com.wangzan101.mika.core.observation.Place
import com.wangzan101.mika.core.verify.AnswerSource
import com.wangzan101.mika.task.DockRoute
import com.wangzan101.mika.task.DockRoutes
import java.util.UUID
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class Route29StorageTest {
    @Test fun priceCardRetainsLastVerifiedValueAndSchedulesOnlyWhileEnabled() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val rows = DockRoutes(app)
        val job = app.getSystemService(android.app.job.JobScheduler::class.java)
        val id = UUID.randomUUID().toString()
        val item = com.wangzan101.mika.core.verify.PriceKey("test.store", "测试商品", "蓝色", "标价")
        val first = com.wangzan101.mika.core.verify.CardPrice(item, 1025)
        fun write(row: DockRoute) {
            rows.prefs.edit().putString(id, Json.encodeToString(DockRoute.serializer(), row)).commit()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
        fun finish(price: com.wangzan101.mika.core.verify.CardPrice, at: Long): DockRoute {
            val active = requireNotNull(rows.get(id)).copy(phase = DockRoute.Phase.Running)
            write(active)
            rows.settled(id, price.display, true, null, emptyList(), "", at = at, quote = price)
            return requireNotNull(rows.get(id))
        }
        try {
            write(DockRoute(id, "核价", id, DockRoute.Phase.Ready, saved = true, templateId = "test",
                cardIntent = com.wangzan101.mika.core.agent.DockCardIntent.DailyPrice, daily = true))
            assertNotNull(job.getPendingJob(42318))
            assertEquals(1, finish(first, 20).priceChanges.size)
            assertEquals(1, finish(first, 30).priceChanges.size)
            val drop = finish(first.copy(low = 995), 40)
            assertEquals(2, drop.priceChanges.size)
            assertEquals(first, drop.previousPrice)
            val mismatch = finish(first.copy(key = item.copy(variant = "红色")), 50)
            assertEquals(DockRoute.Phase.Failed, mismatch.phase)
            assertEquals(40L, mismatch.resultAt)
            assertEquals(995L, mismatch.price!!.low)
            rows.track(id, false, false)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (rows.all().none { it.daily && it.isCard }) assertNull(job.getPendingJob(42318))
        } finally {
            rows.remove(id)
        }
    }

    /** 用独立 UUID 的旧格式夹具验证升级与删除，保留用户自己的记录。 */
    @Test fun legacyCardKeepsItsReceiptAndBecomesIndependentFromItsTemplate() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = DockRoutes(app).prefs
        val id = UUID.randomUUID().toString()
        val tid = "template:" + id
        val route = Route(id, "查看{1}", 1, listOf("test.notes"), emptyMap(), emptyList(), lastUsedMs = 0)
        val legacy = DockRoute(id, "查看首页", id, DockRoute.Phase.Ready, route = route, slots = listOf("首页"),
            saved = true, result = "原结果", resultAt = 123)
        prefs.edit().putString(id, Json.encodeToString(DockRoute.serializer(), legacy)).commit()
        try {
            val rows = DockRoutes(app)
            assertEquals(tid, rows.get(id)?.templateId)
            assertEquals("原结果", rows.get(id)?.result)
            assertTrue(requireNotNull(rows.get(tid)).isTemplate)
            rows.configure(id, listOf("第二页"), false)
            assertEquals(listOf("首页"), rows.get(tid)?.slots)
            rows.remove(tid)
            assertNull(DockRoutes(app).get(tid))
            assertEquals("查看第二页", DockRoutes(app).get(id)?.demand())
        } finally { prefs.edit().remove(id).remove(tid).commit() }
    }

    /** 备用路线只借用明确匹配的需求、应用和口径；旧结果不算本次复验。 */
    @Test fun alternateRequiresTheSameConcreteDemandAppAndAcceptedScope() {
        val rows = DockRoutes(InstrumentationRegistry.getInstrumentation().targetContext)
        val route = Route("first", "查{1}", 1, listOf("test.notes"), emptyMap(), emptyList(), lastUsedMs = 0)
        val failed = DockRoute("draft", "查首页", "trial", DockRoute.Phase.Failed, route,
            listOf("首页"), scope = "当前页")
        val page = com.wangzan101.mika.core.replay.Anchors("test.notes", listOf("首页"))
        val next = com.wangzan101.mika.core.replay.RouteStep("phone_scroll", """{"direction":"down"}""", null, page, page)
        val good = failed.copy(id = "verified", route = route.copy(id = "second", steps = listOf(next)), isTemplate = true,
            saved = true, phase = DockRoute.Phase.Ready)
        assertEquals(good, rows.alternativeFor(failed, listOf(good)))
        for (other in listOf(good.copy(scope = "全部"), good.copy(slots = listOf("收藏")),
            good.copy(route = route.copy(id = "third", packages = listOf("another.app"))),
            good.copy(saved = false), good.copy(route = route.copy(id = "duplicate")))) {
            assertNull(rows.alternativeFor(failed, listOf(other)))
        }
    }

    @Test fun successfulTrialNeedsScopeAndASeparateVerifiedRunBeforeSaving() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val rows = DockRoutes(app)
        val id = UUID.randomUUID().toString()
        val initial = DockRoute(id, "查看榜单", id)
        val candidate = Route(id, initial.goal, 0, listOf("test.notes"), emptyMap(), emptyList(), lastUsedMs = 0)
        rows.prefs.edit().putString(id, Json.encodeToString(DockRoute.serializer(), initial)).commit()
        try {
            rows.settled(id, "今日可见的范围", true, candidate, emptyList(), "", sampleVerified = true)
            val shown = requireNotNull(rows.get(id))
            assertEquals(DockRoute.Phase.Scope, shown.phase)
            assertTrue(shown.sampleVerified)
            assertTrue(runCatching { rows.save(id) }.isFailure)
            assertFalse(requireNotNull(rows.get(id)).saved)
        } finally { rows.prefs.edit().remove(id).commit() }
    }

    @Test fun titleOnlySampleCannotStartDisplayVerificationOrSave() {
        val rows = DockRoutes(InstrumentationRegistry.getInstrumentation().targetContext)
        val id = UUID.randomUUID().toString()
        val initial = DockRoute(id, "核实淘宝身份码展示", id)
        val candidate = Route(id, initial.goal, 0, listOf("com.taobao.taobao"), emptyMap(), emptyList(), lastUsedMs = 0)
        val answer = com.wangzan101.mika.core.uistate.IdentityCodeScreen.ARRIVED_ANSWER
        rows.prefs.edit().putString(id, Json.encodeToString(DockRoute.serializer(), initial)).commit()
        try {
            rows.settled(id, answer, false, candidate, emptyList(), "", "com.taobao.taobao", identityPageOnlySample = true)
            val shown = requireNotNull(rows.get(id))
            assertEquals(answer, shown.sample)
            assertTrue(shown.identityPageOnlySample)
            assertFalse(shown.sampleVerified)
            assertTrue(runCatching { rows.confirmScope(id, initial.goal) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(DockRoute.Phase.Scope, rows.get(id)?.phase)
            assertTrue(runCatching { rows.save(id) }.isFailure)
        } finally { rows.prefs.edit().remove(id).commit() }
    }

    /** 原始卡与独立回执可各自保存本次读数；新模板与旧模板迁移都不能带出无标签码值。 */
    @Test fun migratedAndSavedPickupTemplatesStripUnlabelledReadings() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val rows = DockRoutes(app)
        val id = UUID.randomUUID().toString()
        val legacyId = UUID.randomUUID().toString()
        val route = Route(id, "查看提货码", 0, listOf("test.parcel"), emptyMap(), emptyList(), lastUsedMs = 0)
        val sample = DockRoute(id, route.template, id, phase = DockRoute.Phase.Ready, route = route,
            sample = "C1-222", result = "C1-222", resultGoal = "C1-222", lastAnswer = "C1-222", explorationContext = "C1-222")
        val legacy = sample.copy(id = legacyId, saved = true)
        rows.prefs.edit().putString(id, Json.encodeToString(DockRoute.serializer(), sample))
            .putString(legacyId, Json.encodeToString(DockRoute.serializer(), legacy)).commit()
        try {
            rows.save(id)
            val reopened = DockRoutes(app)
            for (templateId in listOf(id, "template:" + legacyId)) {
                val template = requireNotNull(reopened.get(templateId))
                assertTrue(template.isTemplate)
                assertFalse(Json.encodeToString(DockRoute.serializer(), template).contains("C1-222"))
            }
            assertEquals("C1-222", reopened.get(legacyId)?.result)
        } finally { rows.prefs.edit().remove(id).remove(legacyId).remove("template:" + legacyId).commit() }
    }

    /** 旧自由目标只是需求；重启读库继续使用同一份草稿，不会启动后台任务。 */
    @Test fun legacyFreeGoalImportsOnceAsAnUnstartedDraft() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val goal = "测试需求 " + UUID.randomUUID()
        val id = "goal:" + UUID.nameUUIDFromBytes(goal.encodeToByteArray())
        val old = app.getSharedPreferences("mika_free_templates", android.content.Context.MODE_PRIVATE)
        old.edit().putStringSet("goals", old.getStringSet("goals", emptySet()).orEmpty() + goal).commit()
        val rows = DockRoutes(app)
        try {
            assertEquals(DockRoute.Phase.Draft, rows.get(id)?.phase)
            assertEquals("", rows.get(id)?.taskId)
            assertFalse(requireNotNull(rows.get(id)).isCard)
            assertEquals(1, DockRoutes(app).all().count { it.id == id })
        } finally { rows.remove(id) }
    }

    /** 读取旧成功终态时同时保留原答复、上次结果；独立复验缺证据须允许重做。 */
    @Test fun storedSuccessWithoutRunProofRetainsBothAnswersAndCanBeDeleted() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val rows = DockRoutes(app)
        val row = DockRoute(id, "测试当前值", id, phase = DockRoute.Phase.Running, result = "旧值",
            resultAt = 123, saved = true, templateId = "template:" + id)
        val hub = (app.applicationContext as com.wangzan101.mika.MikaApp).remoteTasks
        // 先走真实收件库初始化，然后只写本测试 UUID 的历史终态。
        assertNull(hub.localTasks.get(id))
        val receipt = android.content.ContentValues().apply {
            put("id", id)
            put("conversation", "mcp-" + UUID.randomUUID())
            put("goal", row.goal)
            put("started", System.currentTimeMillis())
            put("new_chat", 1)
            put("owner", "carddock-local-ui")
            put("result", """{"type":"done","answer":"本轮读数","verified":true,"reviewed":true}""")
        }
        app.openOrCreateDatabase("remote-tasks.db", android.content.Context.MODE_PRIVATE, null).use { db ->
            db.insertOrThrow("receipts", null, receipt)
            try {
                rows.prefs.edit().putString(id, Json.encodeToString(DockRoute.serializer(), row)).commit()
                rows.reconcile(id)
                val restored = requireNotNull(rows.get(id))
                assertEquals(DockRoute.Phase.Failed, restored.phase)
                assertEquals("本轮读数", restored.lastAnswer)
                assertEquals("旧值", restored.result)
                assertEquals(123L, restored.resultAt)
                assertTrue(restored.isCard)
                rows.stop(id, delete = true)
                assertNull(rows.get(id))
            } finally {
                rows.prefs.edit().remove(id).commit()
                db.delete("receipts", "id=?", arrayOf(id))
            }
        }
    }

    /** 真收件库 + JSONL：即使一步完整回放且旧判定为 done，脱敏读屏不能覆盖上一条已核实结果。 */
    @Test fun recoveredReplayAndDoneCannotReplacePickupResultWithUnverifiedAnswer() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val rows = DockRoutes(app)
        val id = UUID.randomUUID().toString()
        val pkg = "test.parcel"
        val page = Anchors(pkg, listOf("待取"))
        val route = Route(id, "查提货码", 0, listOf(pkg), emptyMap(),
            listOf(RouteStep("phone_tap", "{}", null, page, page)), lastUsedMs = 0)
        val row = DockRoute(id, route.template, id, phase = DockRoute.Phase.Running, route = route,
            saved = true, templateId = "template:" + id, result = "上次核实的结果", resultAt = 123)
        val runId = "20261127-000000-" + id.take(6)
        val dir = java.io.File(app.filesDir, "runs/" + runId)
        val log = java.io.File(dir, "events.jsonl")
        val events = listOf(
            RunEvent.ActionTarget(1, 1, "phone_tap", "{}", null, page, page, true),
            RunEvent.Replay(2, route.id, 1, 1, true, ""),
            RunEvent.Observed(3, 2, "提货码：***", id = 3, source = "phone", place = Place.app(pkg), inputs = emptyList()),
            RunEvent.Sources(4, listOf(AnswerSource(3, "read", pkg, null, 2, source = "phone"))),
            RunEvent.TaskEnded(5, "succeeded", "提货码：***"), RunEvent.OutcomeJudged(6, "done"),
        )
        val hub = (app.applicationContext as com.wangzan101.mika.MikaApp).remoteTasks
        assertNull(hub.localTasks.get(id))
        app.openOrCreateDatabase("remote-tasks.db", android.content.Context.MODE_PRIVATE, null).use { db ->
            check(dir.mkdirs())
            try {
                log.writeText(events.joinToString("\n") { RunEvent.json.encodeToString(RunEvent.serializer(), it) })
                db.execSQL("INSERT INTO receipts(id,conversation,goal,started,new_chat,owner,run_id,result) VALUES(?,?,?,?,1,?,?,?)",
                    arrayOf(id, "mcp-" + id, row.goal, System.currentTimeMillis(), "carddock-local-ui", runId,
                        """{"type":"done","answer":"提货码：C1-222","verified":true,"reviewed":true}"""))
                for (phase in listOf(DockRoute.Phase.Running, DockRoute.Phase.Checking)) {
                    rows.prefs.edit().putString(id, Json.encodeToString(DockRoute.serializer(), row.copy(phase = phase))).commit()
                    rows.reconcile(id)
                    val recovered = requireNotNull(rows.get(id))
                    assertEquals(DockRoute.Phase.Failed, recovered.phase)
                    assertEquals(row.result, recovered.result)
                    assertEquals(row.resultAt, recovered.resultAt)
                    assertEquals("提货码：C1-222", recovered.lastAnswer)
                    assertTrue(recovered.issue.contains("实际码值"))
                }
            } finally {
                rows.prefs.edit().remove(id).commit()
                db.delete("receipts", "id=?", arrayOf(id))
                log.delete()
                dir.delete()
            }
        }
    }

    /** 停止请求被受理时记录仍归草稿；确认删除并收到终态后不得被旧回调重建。 */
    @Test fun cancellationClosesDraftAndDeletionSurvivesLateCallback() {
        val rows = DockRoutes(InstrumentationRegistry.getInstrumentation().targetContext)
        val id = UUID.randomUUID().toString()
        val pending = DockRoute(id, "检查页面", id, stopping = true)
        try {
            rows.prefs.edit().putString(id, Json.encodeToString(DockRoute.serializer(), pending)).commit()
            rows.fail(id, "任务已停止。", cancelled = true)
            val stopped = requireNotNull(rows.get(id))
            assertEquals(DockRoute.Phase.Stopped, stopped.phase)
            assertFalse(stopped.stopping)
            assertFalse(stopped.isCard)
            rows.prefs.edit().putString(id, Json.encodeToString(DockRoute.serializer(),
                pending.copy(deleteAfterStop = true))).commit()
            rows.fail(id, "任务已停止。", cancelled = true)
            rows.settled(id, "延迟答复", true, null, emptyList(), "")
            assertNull(rows.get(id))
        } finally { rows.prefs.edit().remove(id).commit() }
    }
}
