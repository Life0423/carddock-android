package com.life0423.carddock.core.session

import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.model.RunPhase
import com.life0423.carddock.core.port.CardStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class CardCollectionState(
    val loading: Boolean = true,
    val cards: List<CardRecord> = emptyList(),
)

/** Serializes collection changes so an older save cannot overwrite a newer run. */
internal class CardCollection(
    private val store: CardStore,
    private val notify: (String) -> Unit,
    private val now: () -> Long,
    private val hasLiveJob: (cardId: String) -> Boolean = { false },
) {
    private val lock = Mutex()
    private val mutableState = MutableStateFlow(CardCollectionState())
    val state = mutableState.asStateFlow()
    private var readable = true

    /**
     * 启动时加载。load 返回 null 表示没有文件，用 [initialCards] 创建。
     * 读失败不覆盖原文件，并把集合标成不可写，直到整表替换成功。
     */
    suspend fun initialize(initialCards: List<CardRecord>) = lock.withLock {
        val stored = try {
            store.load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            readable = false
            mutableState.value = CardCollectionState(loading = false)
            notify("本地演示数据无法读取，原文件未覆盖。可在“我的”中重置演示。")
            return@withLock
        }
        val restored = settleBusyLoadedFromDisk(stored ?: initialCards)
        currentCoroutineContext().ensureActive()
        try {
            withContext(NonCancellable) {
                if (stored == null || restored != stored) store.save(restored)
                mutableState.value = CardCollectionState(loading = false, cards = restored)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 读已经成功。写回中断态失败不能当成文件损坏，否则界面会空掉并引导用户用种子卡重置。
            mutableState.value = CardCollectionState(loading = false, cards = restored)
            notify("恢复状态未能写回，原内容仍在。可以稍后重试。")
        }
    }

    fun find(id: String): CardRecord? = state.value.cards.find { it.id == id }

    /**
     * 在锁内基于当前列表计算新列表并持久化。
     * 返回 false：仍在加载或不可写、变换前后列表相等，或保存没有得到一次已确认的提交。
     * 读回抛错只表示无法确认，磁盘可能已经是新内容。读回得到 null 或另一份内容，则是确认没提交。
     * 读回内容与本次要写的列表相同则返回 true。取消会抛出，不是 false。
     */
    suspend fun update(transform: (List<CardRecord>) -> List<CardRecord>): Boolean = lock.withLock {
        if (!readable || state.value.loading) {
            notify("本地数据尚不可写，请先完成加载或重置演示。")
            return@withLock false
        }
        val current = state.value.cards
        val next = transform(current)
        if (next == current) return@withLock false
        persist(next)
    }

    /** 整表替换。成功时恢复为可写；读失败后只有这条路径能重新允许 update。 */
    suspend fun replace(cards: List<CardRecord>): Boolean = lock.withLock {
        val saved = persist(cards)
        if (saved) readable = true
        saved
    }

    private suspend fun persist(cards: List<CardRecord>): Boolean {
        return try {
            currentCoroutineContext().ensureActive()
            // 成功路径里，取消不能把磁盘提交和内存发布拆开。失败后的对齐在下面，并且不能被取消跳过。
            withContext(NonCancellable) {
                store.save(cards)
                mutableState.value = CardCollectionState(loading = false, cards = cards)
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 调用方可能已经取消。先对齐磁盘再把取消传出去；协调被跳过时，旧内存仍可写，会盖掉已提交的新卡。
            val reconciled = withContext(NonCancellable) { reconcileAfterFailedSave(cards) }
            currentCoroutineContext().ensureActive()
            reconciled
        }
    }

    /** 返回 true 表示磁盘上已经是本次要保存的集合。读失败只说明无法确认，不等于没有提交；那时暂停后续写入。 */
    private suspend fun reconcileAfterFailedSave(attempted: List<CardRecord>): Boolean {
        val onDisk = try {
            store.load()
        } catch (cancelled: CancellationException) {
            // 取消不是读失败，不能在这里当成未提交。外层会在不可取消的协调之后再传播取消。
            throw cancelled
        } catch (_: Exception) {
            readable = false
            notify("保存结果无法确认，已暂停写入，避免覆盖磁盘上的卡片。")
            return false
        }
        if (onDisk == null) {
            notify("保存失败，本次更改未提交。请检查设备空间后重试。")
            return false
        }
        // 磁盘就是这次提交时，进行中的阶段原样留下。没写上时收掉没有活协程的忙碌卡；没有活协程不表示这是崩溃残留。
        val committed = onDisk == attempted
        val published = if (committed) onDisk else settleBusyLoadedFromDisk(onDisk)
        mutableState.value = CardCollectionState(loading = false, cards = published)
        if (committed) return true
        notify("保存失败，本次更改未提交。请检查设备空间后重试。")
        return false
    }

    /**
     * 从磁盘装回的忙碌卡，没有活协程时收成已中断。
     * 启动时任务表是空的，上次进程留下的 QUEUED/RUNNING/NEEDS_USER 会全部收掉。
     * 无活协程不等于崩溃残留：本进程里正常结束设备协程的 NEEDS_USER 也会被收掉，这是当前策略。
     * 协程还在的任务留在内存里的阶段。
     */
    private fun settleBusyLoadedFromDisk(cards: List<CardRecord>): List<CardRecord> {
        val current = state.value.cards.associateBy { it.id }
        return cards.map { card ->
            if (hasLiveJob(card.id)) current[card.id] ?: card else interruptStaleBusy(card)
        }
    }

    private fun interruptStaleBusy(card: CardRecord): CardRecord {
        val run = card.run ?: return card
        if (run.phase !in setOf(RunPhase.QUEUED, RunPhase.RUNNING, RunPhase.NEEDS_USER)) return card
        return card.copy(run = run.copy(
            phase = RunPhase.INTERRUPTED,
            message = "上次运行已中断，请重新刷新核验",
            endedAt = now(),
        ))
    }

    /**
     * update 返回 false、这次运行的终态没进集合时使用。
     * 只改内存里 runId 相同的那次运行，不再写盘。对不上就不动。
     */
    fun showStorageFailure(cardId: String, runId: String) {
        val current = state.value
        mutableState.value = current.copy(cards = current.cards.map { card ->
            if (card.id == cardId && card.run?.id == runId) {
                card.copy(run = card.run.copy(
                    phase = RunPhase.FAILED,
                    message = "结果未能保存，上次有效数据仍保留",
                    endedAt = now(),
                ))
            } else card
        })
    }
}
