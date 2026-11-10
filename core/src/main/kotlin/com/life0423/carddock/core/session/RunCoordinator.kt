package com.life0423.carddock.core.session

import com.life0423.carddock.core.engine.EngineResult
import com.life0423.carddock.core.engine.RuleEngine
import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.model.RunInfo
import com.life0423.carddock.core.model.RunPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One operation lane is shared with creator trials; observations never start work. */
internal class RunCoordinator(
    private val cards: CardCollection,
    private val engine: RuleEngine,
    private val lane: Mutex,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val newId: () -> String,
) {
    private val jobs = mutableMapOf<String, Job>()

    fun start(cardId: String) {
        synchronized(jobs) {
            if (jobs[cardId]?.isActive == true || cards.find(cardId) == null) return
            val job = scope.launch(start = CoroutineStart.LAZY) { run(cardId) }
            jobs[cardId] = job
            job.start()
        }
    }

    fun cancel(cardId: String) {
        val job = synchronized(jobs) { jobs[cardId] }
        if (job != null && !job.isCompleted) {
            job.cancel()
            return
        }
        // A user handoff releases the device lane; its run still needs an explicit cancel action.
        val run = cards.find(cardId)?.run ?: return
        if (run.phase == RunPhase.NEEDS_USER) scope.launch {
            update(cardId, run.id) { it.copy(run = it.run!!.copy(
                phase = RunPhase.CANCELLED,
                message = "已取消接管，本次没有更新结果",
                endedAt = now(),
            )) }
        }
    }

    suspend fun cancelAndJoin(cardId: String) {
        val job = synchronized(jobs) { jobs[cardId] }
        job?.cancel()
        job?.join()
    }

    suspend fun cancelAll() {
        val active = synchronized(jobs) { jobs.values.toList() }
        active.forEach { it.cancel() }
        active.forEach { it.join() }
    }

    /** 这张卡的协程还没结束，取消过程中也算。磁盘上的忙碌卡没有对应条目，那是上次进程留下的。 */
    fun hasLiveJob(cardId: String): Boolean =
        synchronized(jobs) { jobs[cardId]?.isCompleted == false }

    private suspend fun run(cardId: String) {
        val job = currentCoroutineContext()[Job]
        val runId = newId()
        try {
            val card = cards.find(cardId) ?: return
            val queued = cards.update { current -> current.map {
                if (it.id == cardId) it.copy(run = RunInfo(
                    id = runId,
                    phase = RunPhase.QUEUED,
                    message = "等待设备操作通道",
                    totalSteps = card.rule.steps.size,
                    startedAt = now(),
                )) else it
            } }
            if (!queued) {
                // 排队没写上时，重载可能把磁盘里没有对应 job 的 RUNNING/QUEUED 装回来。
                // 只收敛这一张卡；这次尚未开始读设备，也不取消其他正在运行的任务。
                val stale = cards.find(cardId)?.run
                if (stale != null && stale.phase in setOf(RunPhase.QUEUED, RunPhase.RUNNING)) {
                    cards.showStorageFailure(cardId, stale.id)
                }
                return
            }
            lane.withLock {
                currentCoroutineContext().ensureActive()
                update(cardId, runId) { it.copy(run = it.run!!.copy(
                    phase = RunPhase.RUNNING,
                    message = "正在读取模拟设备",
                )) }
                val result = engine.execute(card.rule) { progress ->
                    update(cardId, runId) { it.copy(run = it.run!!.copy(
                        currentStep = progress.currentStep,
                        totalSteps = progress.totalSteps,
                        message = progress.message,
                    )) }
                }
                currentCoroutineContext().ensureActive()
                val committed = update(cardId, runId) { current ->
                    val run = current.run!!
                    when (result) {
                        is EngineResult.Success -> current.copy(
                            snapshot = result.snapshot,
                            run = run.copy(phase = RunPhase.SUCCEEDED,
                                message = "核验通过 · 演示数据", endedAt = now()),
                        )
                        is EngineResult.NeedsUser -> current.copy(run = run.copy(
                            phase = RunPhase.NEEDS_USER, message = result.message, endedAt = now(),
                        ))
                        is EngineResult.Failure -> current.copy(run = run.copy(
                            phase = RunPhase.FAILED, message = result.message, endedAt = now(),
                        ))
                    }
                }
                if (!committed) cards.showStorageFailure(cardId, runId)
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                // update 为 false 时终态没有发布。若不改内存阶段，job 清掉后取消按钮不再有目标，卡片会停在 RUNNING/QUEUED。
                val persisted = update(cardId, runId) { it.copy(run = it.run!!.copy(
                    phase = RunPhase.CANCELLED,
                    message = "已取消，上次有效结果仍保留",
                    endedAt = now(),
                )) }
                if (!persisted) cards.showStorageFailure(cardId, runId)
            }
            throw cancelled
        } catch (_: Exception) {
            val persisted = update(cardId, runId) { it.copy(run = it.run!!.copy(
                phase = RunPhase.FAILED,
                message = "本次读取失败，请重试",
                endedAt = now(),
            )) }
            if (!persisted) cards.showStorageFailure(cardId, runId)
        } finally {
            synchronized(jobs) { if (jobs[cardId] === job) jobs.remove(cardId) }
        }
    }

    private suspend fun update(
        cardId: String,
        runId: String,
        transform: (CardRecord) -> CardRecord,
    ): Boolean = cards.update { current -> current.map { card ->
        if (card.id == cardId && card.run?.id == runId) transform(card) else card
    } }
}
