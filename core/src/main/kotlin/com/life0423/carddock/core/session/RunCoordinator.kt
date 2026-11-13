package com.life0423.carddock.core.session

import com.life0423.carddock.core.engine.EngineResult
import com.life0423.carddock.core.engine.RuleEngine
import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.model.RunInfo
import com.life0423.carddock.core.model.RunPhase
import com.life0423.carddock.core.port.ReadOptions
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

/**
 * One operation lane is shared with creator trials; observations never start work.
 * 收集状态或进度回调不会另开一次设备读取，只有 [start] 会。任务表用同步块保护，和协程调度器无关。
 */
internal class RunCoordinator(
    private val cards: CardCollection,
    private val engine: RuleEngine,
    private val lane: Mutex,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val newId: () -> String,
) {
    private val jobs = mutableMapOf<String, Job>()

    /**
     * 已有 isActive 的协程，或卡片不在集合里，直接返回。
     * 取消收尾中 isActive 为 false，这道检查不会拦住，新的一次会盖掉表项。
     * 用 LAZY 先放入表再 start。若先 launch 再登记，只有协程在第一次挂起前就进了 finally，
     * 收尾才摘不掉这次 job：start 正持有同一把可重入的锁。launch 返回后会留下一个已结束任务。
     * 正常读设备会先挂起，不走这条。
     */
    fun start(cardId: String, appPrice: Boolean = false): Job? {
        val requestedAt = if (appPrice) now() else null
        synchronized(jobs) {
            jobs[cardId]?.takeIf { it.isActive }?.let { return it }
            if (cards.find(cardId) == null) return null
            val job = scope.launch(start = CoroutineStart.LAZY) { run(cardId, appPrice, requestedAt) }
            jobs[cardId] = job
            job.start()
            return job
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

    /** 取消并等到收尾写完。删除卡片前要用这个，而不是只发取消。 */
    suspend fun cancelAndJoin(cardId: String) {
        val job = synchronized(jobs) { jobs[cardId] }
        job?.cancel()
        job?.join()
    }

    /** 只取消调用当时表里的任务。之后才登记的不在这次等待里。 */
    suspend fun cancelAll() {
        val active = synchronized(jobs) { jobs.values.toList() }
        active.forEach { it.cancel() }
        active.forEach { it.join() }
    }

    /** 按卡片查询未结束的协程，取消收尾中也返回 true。
     * 无活协程不等于崩溃残留：正常 NEEDS_USER 同样会结束设备协程；恢复策略由 CardCollection 决定。
     */
    fun hasLiveJob(cardId: String): Boolean =
        synchronized(jobs) { jobs[cardId]?.isCompleted == false }

    private suspend fun run(cardId: String, appPrice: Boolean, requestedAt: Long?) {
        val job = currentCoroutineContext()[Job]
        val runId = newId()
        try {
            val card = cards.find(cardId) ?: return
            val queued = cards.update { current -> current.map {
                if (it.id == cardId) it.copy(priceVerificationFailed = appPrice || it.priceVerificationFailed, run = RunInfo(
                    id = runId,
                    phase = RunPhase.QUEUED,
                    message = "等待设备操作通道",
                    totalSteps = card.rule.steps.size,
                    startedAt = now(),
                    appPrice = appPrice,
                )) else it
            } }
            if (!queued) {
                // 排队没写上。这次 job 还活着，对账会留下内存里的旧阶段，不会把磁盘上的忙碌阶段原样装回来。
                // 没有活协程的其他卡会在对账里收成已中断。这里只在这张卡的内存阶段仍是排队或运行中时改成失败。
                // 还没进设备通道，也不取消其他任务。
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
                    message = "正在读取页面",
                )) }
                val result = engine.execute(card.rule, ReadOptions(card.localTarget, appPrice, requestedAt)) { progress ->
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
                        is EngineResult.Success -> if (appPrice && result.appPrice == null) {
                            current.copy(run = run.copy(
                                phase = RunPhase.FAILED, message = "未核验到 App 展示价", endedAt = now(),
                            ))
                        } else current.copy(
                            snapshot = result.snapshot,
                            appPrice = result.appPrice ?: current.appPrice,
                            priceVerificationFailed = if (appPrice) false else current.priceVerificationFailed,
                            run = run.copy(phase = RunPhase.SUCCEEDED,
                                message = "核验通过 · ${result.snapshot.sourceLabel}", endedAt = now()),
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

    /**
     * 只变换仍挂着这次 [runId] 的那张卡。runId 对不上或卡已删除时列表不变，返回 false。
     * false 也可能是持久化没能确认提交，调用方要另做内存收尾。
     */
    private suspend fun update(
        cardId: String,
        runId: String,
        transform: (CardRecord) -> CardRecord,
    ): Boolean = cards.update { current -> current.map { card ->
        if (card.id == cardId && card.run?.id == runId) transform(card) else card
    } }
}
