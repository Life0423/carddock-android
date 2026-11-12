package com.wangzan101.mika.workspace

import com.wangzan101.mika.experiment.JD_A_SKU
import com.wangzan101.mika.experiment.JD_A_VARIANT
import com.wangzan101.mika.experiment.JdShareVerifier

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WorkspaceDatabaseTest {
    @Test fun jdShareParserCompilesAndMatchesOnAndroid() {
        val html = """<script>window._itemOnly = ({
            "item":{"skuId":"$JD_A_SKU","skuName":"显示器","newColorSize":[{"skuId":"$JD_A_SKU","1":"$JD_A_VARIANT"}]}
        });</script>"""
        assertEquals("JD_IDENTITY_CONFIRMED", JdShareVerifier().verifyPage(html).outcome)
    }

    @Test fun migrationPreservesV1EvidenceAndKeepsOrderSnapshotOnFailure() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "workspace-migration-test.db"
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, 0, null).use { old ->
            old.execSQL("CREATE TABLE rules (ruleId TEXT NOT NULL, version INTEGER NOT NULL, json TEXT NOT NULL, PRIMARY KEY(ruleId, version))")
            old.execSQL("CREATE TABLE cards (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, ruleId TEXT NOT NULL, ruleVersion INTEGER NOT NULL, source TEXT NOT NULL, lastValidText TEXT NOT NULL, lastSuccessAt INTEGER NOT NULL, lastAttemptAt INTEGER NOT NULL, status TEXT NOT NULL, reason TEXT NOT NULL)")
            old.execSQL("CREATE TABLE runs (id TEXT NOT NULL PRIMARY KEY, cardId TEXT NOT NULL, startedAt INTEGER NOT NULL, finishedAt INTEGER NOT NULL, outcome TEXT NOT NULL, message TEXT NOT NULL, elapsedMs INTEGER NOT NULL, modelCallsDelta INTEGER NOT NULL)")
            old.execSQL("CREATE INDEX index_runs_cardId ON runs(cardId)")
            old.execSQL("INSERT INTO rules VALUES ('cainiao', 1, 'v1 rule')")
            old.execSQL("INSERT INTO cards VALUES ('old', '菜鸟', 'cainiao', 1, '菜鸟', '暂无待取包裹', 100, 101, 'FRESH_VALID', '成功')")
            old.execSQL("INSERT INTO runs VALUES ('run-old', 'old', 90, 100, 'EMPTY_CONFIRMED', '成功', 10, 0)")
            old.version = 1
        }
        val db = Room.databaseBuilder(context, WorkspaceDatabase::class.java, name)
            .addMigrations(workspaceMigration1To2, workspaceMigration2To3).build()
        val dao = db.dao()
        val old = WorkspaceCard("old", "菜鸟", "cainiao", 1, "菜鸟", "暂无待取包裹", 100, 101, CardStatus.FRESH_VALID, "成功")
        assertEquals(old, dao.cards().single())
        assertEquals(StoredRule("cainiao", 1, "v1 rule"), dao.rule("cainiao", 1))
        assertEquals(WorkspaceRun("run-old", "old", 90, 100, "EMPTY_CONFIRMED", "成功", 10, 0), dao.runs("old").single())
        val order = WorkspaceCard("order", "美团", "order-rule", 1, "美团", "已完成", 200, 200,
            CardStatus.FRESH_VALID, "成功", "local-config", "structured-snapshot")
        dao.insertCard(order)
        dao.updateCard(order.afterAttempt("TIMEOUT", "超时", "", 300))
        assertEquals(listOf(old, order.copy(lastAttemptAt = 300, status = CardStatus.STALE_CACHED, reason = "超时")),
            dao.cards())
        db.close()
        context.deleteDatabase(name)
        Unit
    }

    @Test fun committedCardsSurviveReopenAndDeletingOneKeepsSharedRuleAndOtherHistory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "workspace-test.db"
        context.deleteDatabase(name)
        var db = Room.databaseBuilder(context, WorkspaceDatabase::class.java, name).build()
        val rule = StoredRule("r", 1, "test-only")
        val a = WorkspaceCard("a", "测试a", "r", 1, "测试", "旧结果", 100, 100, CardStatus.FRESH_VALID, "成功")
        val b = a.copy(id = "b", title = "测试b")
        db.withTransaction {
            db.dao().insertRule(rule)
            db.dao().insertCard(a)
            db.dao().insertCard(b)
            db.dao().insertRun(WorkspaceRun("run-a", "a", 100, 100, "EMPTY_CONFIRMED", "成功", 1, 0))
            db.dao().insertRun(WorkspaceRun("run-b", "b", 100, 100, "EMPTY_CONFIRMED", "成功", 1, 0))
        }
        db.close()
        db = Room.databaseBuilder(context, WorkspaceDatabase::class.java, name).build()
        assertEquals(listOf(a, b), db.dao().cards())
        db.withTransaction {
            db.dao().updateCard(a.afterAttempt("TIMEOUT", "超时", "", 200))
            db.dao().insertRun(WorkspaceRun("failure-a", "a", 200, 200, "TIMEOUT", "超时", 100, 0))
        }
        assertEquals(a.lastValidText, db.dao().cards().first().lastValidText)
        assertEquals(a.lastSuccessAt, db.dao().cards().first().lastSuccessAt)
        assertEquals(b, db.dao().cards().last())
        db.withTransaction { db.dao().deleteRuns("a"); db.dao().deleteCard("a") }
        assertEquals(listOf(b), db.dao().cards())
        assertEquals(rule, db.dao().rule("r", 1))
        assertEquals(1, db.dao().runs("b").size)
        assertTrue(db.dao().runs("a").isEmpty())
        db.close()
        context.deleteDatabase(name)
        Unit
    }

    @Test fun interruptedRefreshRecoversAsCachedWithoutChangingSuccessEvidence() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, WorkspaceDatabase::class.java).build()
        val old = WorkspaceCard("a", "测试", "r", 1, "测试", "旧结果", 100, 200, CardStatus.REFRESHING, "刷新中")
        db.dao().insertCard(old)
        db.dao().recoverInterrupted()
        val recovered = db.dao().cards().single()
        assertEquals(CardStatus.STALE_CACHED, recovered.status)
        assertEquals(old.lastSuccessAt, recovered.lastSuccessAt)
        assertEquals(old.lastValidText, recovered.lastValidText)
        assertEquals(old.lastAttemptAt, recovered.lastAttemptAt)
        db.close()
    }
}
