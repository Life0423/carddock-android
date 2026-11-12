package com.wangzan101.mika.workspace

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.Serializable

@Serializable
enum class CardStatus { FRESH_VALID, REFRESHING, STALE_CACHED, NEED_HANDOFF, NEED_HEAL }

class WorkspaceConverters {
    @TypeConverter fun encode(value: CardStatus): String = value.name
    @TypeConverter fun decode(value: String): CardStatus = CardStatus.valueOf(value)
}

@Serializable
@Entity(tableName = "rules", primaryKeys = ["ruleId", "version"])
data class StoredRule(val ruleId: String, val version: Int, val json: String)

@Serializable
@Entity(tableName = "cards")
data class WorkspaceCard(
    @PrimaryKey val id: String,
    val title: String,
    val ruleId: String,
    val ruleVersion: Int,
    val source: String,
    val lastValidText: String,
    val lastSuccessAt: Long,
    val lastAttemptAt: Long,
    val status: CardStatus,
    val reason: String,
    val localTargetJson: String? = null,
    val snapshotJson: String? = null,
    val previousRuleId: String? = null,
    val previousRuleVersion: Int? = null,
)

@Entity(tableName = "shopping_task")
data class ShoppingTask(
    @PrimaryKey val id: Int = 1,
    val productA: String,
    val productB: String,
    val note: String,
    val savedAt: Long,
    val lastCheckedAt: Long?,
    val lastCheck: String,
)

@Serializable
@Entity(tableName = "runs", indices = [Index("cardId")])
data class WorkspaceRun(
    @PrimaryKey val id: String,
    val cardId: String,
    val startedAt: Long,
    val finishedAt: Long,
    val outcome: String,
    val message: String,
    val elapsedMs: Long,
    val modelCallsDelta: Long,
)

@Dao
interface WorkspaceDao {
    @Query("SELECT * FROM cards ORDER BY rowid") suspend fun cards(): List<WorkspaceCard>
    @Query("SELECT * FROM rules WHERE ruleId = :id AND version = :version") suspend fun rule(id: String, version: Int): StoredRule?
    @Query("SELECT * FROM runs WHERE cardId = :id ORDER BY finishedAt DESC, rowid DESC") suspend fun runs(id: String): List<WorkspaceRun>
    @Query("SELECT * FROM rules") suspend fun rules(): List<StoredRule>
    @Query("SELECT * FROM runs ORDER BY rowid") suspend fun allRuns(): List<WorkspaceRun>
    @Query("SELECT * FROM shopping_task WHERE id = 1") suspend fun shoppingTask(): ShoppingTask?
    @Upsert suspend fun saveShoppingTask(task: ShoppingTask)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertRule(rule: StoredRule)
    @Insert suspend fun insertCard(card: WorkspaceCard)
    @Update suspend fun updateCard(card: WorkspaceCard)
    @Insert suspend fun insertRun(run: WorkspaceRun)
    @Query("UPDATE runs SET elapsedMs = :elapsed WHERE id = :id") suspend fun duration(id: String, elapsed: Long)
    @Query("DELETE FROM cards WHERE id = :id") suspend fun deleteCard(id: String)
    @Query("DELETE FROM runs WHERE cardId = :id") suspend fun deleteRuns(id: String)
    @Query("UPDATE cards SET status = 'STALE_CACHED', reason = '上次刷新被进程退出中断；保留上次成功结果。' WHERE status = 'REFRESHING'") suspend fun recoverInterrupted()
}

@Database(entities = [StoredRule::class, WorkspaceCard::class, WorkspaceRun::class, ShoppingTask::class], version = 3, exportSchema = true)
@TypeConverters(WorkspaceConverters::class)
abstract class WorkspaceDatabase : RoomDatabase() { abstract fun dao(): WorkspaceDao }

internal val workspaceMigration1To2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE cards ADD COLUMN localTargetJson TEXT")
        db.execSQL("ALTER TABLE cards ADD COLUMN snapshotJson TEXT")
    }
}

internal val workspaceMigration2To3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE cards ADD COLUMN previousRuleId TEXT")
        db.execSQL("ALTER TABLE cards ADD COLUMN previousRuleVersion INTEGER")
        db.execSQL("""CREATE TABLE IF NOT EXISTS shopping_task (
            id INTEGER NOT NULL PRIMARY KEY, productA TEXT NOT NULL, productB TEXT NOT NULL,
            note TEXT NOT NULL, savedAt INTEGER NOT NULL, lastCheckedAt INTEGER, lastCheck TEXT NOT NULL
        )""")
    }
}

/** Successful evidence alone replaces the snapshot. Failure only changes attempt metadata. */
fun WorkspaceCard.afterAttempt(outcome: String, message: String, displayText: String, at: Long,
                               snapshot: String? = null): WorkspaceCard {
    val success = outcome == "EMPTY_CONFIRMED" ||
        outcome in setOf("ORDER_CONFIRMED", "JD_IDENTITY_CONFIRMED") && snapshot != null
    return copy(lastAttemptAt = at, reason = message,
        status = when (outcome) {
            "EMPTY_CONFIRMED" -> CardStatus.FRESH_VALID
            "ORDER_CONFIRMED", "JD_IDENTITY_CONFIRMED" -> if (success) CardStatus.FRESH_VALID else CardStatus.STALE_CACHED
            "NEED_HANDOFF" -> CardStatus.NEED_HANDOFF
            "APP_VERSION_MISMATCH", "INVALID_RULE" -> CardStatus.NEED_HEAL
            else -> CardStatus.STALE_CACHED
        },
        lastValidText = if (success) displayText else lastValidText,
        lastSuccessAt = if (success) at else lastSuccessAt,
        snapshotJson = if (success) snapshot else snapshotJson)
}
