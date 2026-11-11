package com.wangzan101.mika.workspace

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WorkspaceDatabaseTest {
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
