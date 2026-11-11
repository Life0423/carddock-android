package com.wangzan101.mika.workspace

import androidx.room.*
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
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertRule(rule: StoredRule)
    @Insert suspend fun insertCard(card: WorkspaceCard)
    @Update suspend fun updateCard(card: WorkspaceCard)
    @Insert suspend fun insertRun(run: WorkspaceRun)
    @Query("UPDATE runs SET elapsedMs = :elapsed WHERE id = :id") suspend fun duration(id: String, elapsed: Long)
    @Query("DELETE FROM cards WHERE id = :id") suspend fun deleteCard(id: String)
    @Query("DELETE FROM runs WHERE cardId = :id") suspend fun deleteRuns(id: String)
    @Query("UPDATE cards SET status = 'STALE_CACHED', reason = '上次刷新被进程退出中断；保留上次成功结果。' WHERE status = 'REFRESHING'") suspend fun recoverInterrupted()
}

@Database(entities = [StoredRule::class, WorkspaceCard::class, WorkspaceRun::class], version = 1, exportSchema = true)
@TypeConverters(WorkspaceConverters::class)
abstract class WorkspaceDatabase : RoomDatabase() { abstract fun dao(): WorkspaceDao }

/** Successful evidence alone replaces the snapshot. Failure only changes attempt metadata. */
fun WorkspaceCard.afterAttempt(outcome: String, message: String, verifiedEmpty: String, at: Long): WorkspaceCard =
    copy(lastAttemptAt = at, reason = message,
        status = when (outcome) {
            "EMPTY_CONFIRMED" -> CardStatus.FRESH_VALID
            "NEED_HANDOFF" -> CardStatus.NEED_HANDOFF
            "APP_VERSION_MISMATCH", "INVALID_RULE" -> CardStatus.NEED_HEAL
            else -> CardStatus.STALE_CACHED
        },
        lastValidText = if (outcome == "EMPTY_CONFIRMED") verifiedEmpty else lastValidText,
        lastSuccessAt = if (outcome == "EMPTY_CONFIRMED") at else lastSuccessAt)
