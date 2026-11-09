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
) {
    private val lock = Mutex()
    private val mutableState = MutableStateFlow(CardCollectionState())
    val state = mutableState.asStateFlow()
    private var readable = true

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
        val restored = (stored ?: initialCards).map { card ->
            val run = card.run
            if (run != null && run.phase in setOf(RunPhase.QUEUED, RunPhase.RUNNING, RunPhase.NEEDS_USER)) {
                card.copy(run = run.copy(
                    phase = RunPhase.INTERRUPTED,
                    message = "上次运行已中断，请重新刷新核验",
                    endedAt = now(),
                ))
            } else card
        }
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

    suspend fun replace(cards: List<CardRecord>): Boolean = lock.withLock {
        val saved = persist(cards)
        if (saved) readable = true
        saved
    }

    private suspend fun persist(cards: List<CardRecord>): Boolean {
        return try {
            currentCoroutineContext().ensureActive()
            // Commit disk and memory together even if an action is cancelled during the write.
            withContext(NonCancellable) {
                store.save(cards)
                mutableState.value = CardCollectionState(loading = false, cards = cards)
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            notify("保存失败，本次更改未提交。请检查设备空间后重试。")
            false
        }
    }

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
