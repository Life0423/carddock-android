package com.life0423.carddock.core.testing

import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.model.TemplateDefinition
import com.life0423.carddock.core.port.CardStore
import com.life0423.carddock.core.port.CompileResult
import com.life0423.carddock.core.port.DeviceGateway
import com.life0423.carddock.core.port.DeviceResponse
import com.life0423.carddock.core.port.DeviceSnapshot
import com.life0423.carddock.core.port.RuleCompiler
import com.life0423.carddock.core.session.DemoScenario
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * 会话测试共用的内存卡片存储。
 *
 * [disk] 是当前已提交内容，null 表示还没有文件。
 * [failRead] 在 load 时抛出；[failWrite] 在替换 [disk] 之前抛出；[failAfterCommit] 在 [disk] 已经换成新集合之后抛出。
 * [beforeLoad] 与 [beforeSave] 先于上述失败点执行，用来把保存停在提交前，或让 load 看见调用方取消。
 */
class MemoryCardStore(initial: List<CardRecord>?) : CardStore {
    var disk: List<CardRecord>? = initial
    var failRead = false
    var failWrite = false
    var failAfterCommit = false
    var writes = 0
    var beforeLoad: (suspend () -> Unit)? = null
    var beforeSave: (suspend (List<CardRecord>) -> Unit)? = null

    override suspend fun load(): List<CardRecord>? {
        beforeLoad?.invoke()
        if (failRead) throw IOException("injected read failure")
        return disk
    }

    override suspend fun save(cards: List<CardRecord>) {
        writes += 1
        beforeSave?.invoke(cards)
        if (failWrite) throw IOException("injected precommit write failure")
        disk = cards.toList()
        if (failAfterCommit) throw IOException("injected postcommit readback failure")
    }
}

/**
 * 按 sourceId 返回预先登记的观察目标。
 *
 * 响应里的 targetKey 来自 [observedTargets]，不回写本次请求的 targetKey。
 * 规则被改成伪造目标时，引擎会得到对象不匹配，而不是被替身放行。
 * [delayMillis] 用虚拟时钟挂起，便于测试推进排队和取消。
 */
class ObservedDevice(
    private val observedTargets: Map<String, String>,
    private val fields: Map<String, String> = mapOf("status" to "已更新"),
    private val delayMillis: Long = 1_000,
) : DeviceGateway {
    private var scenario = DemoScenario.NORMAL
    var calls = 0
    var concurrentCalls = 0
    var maxConcurrentCalls = 0
    var cancelledCalls = 0
    val requests = mutableListOf<Pair<String, String>>()

    fun setScenario(value: DemoScenario) {
        scenario = value
    }

    override suspend fun inspect(sourceId: String, targetKey: String): DeviceResponse {
        calls += 1
        requests += sourceId to targetKey
        concurrentCalls += 1
        maxConcurrentCalls = maxOf(maxConcurrentCalls, concurrentCalls)
        val selected = scenario
        val observed = observedTargets[sourceId] ?: UNMATCHED_TARGET
        try {
            delay(delayMillis)
            return when (selected) {
                DemoScenario.NORMAL -> DeviceResponse.Ready(DeviceSnapshot(sourceId, observed, fields))
                DemoScenario.NEEDS_USER -> DeviceResponse.NeedsUser("演示接管")
                DemoScenario.READ_FAILURE -> DeviceResponse.Failed("演示失败")
                DemoScenario.WRONG_TARGET -> DeviceResponse.Ready(
                    DeviceSnapshot(sourceId, "wrong", mapOf("status" to "不应显示")),
                )
            }
        } catch (cancelled: CancellationException) {
            cancelledCalls += 1
            throw cancelled
        } finally {
            concurrentCalls -= 1
        }
    }

    private companion object {
        const val UNMATCHED_TARGET = "unobserved"
    }
}

/** 固定返回同一模板。prompt 不参与匹配，控制器测试不必再各写一份编译器。 */
class FixedCompiler(private val template: TemplateDefinition) : RuleCompiler {
    override suspend fun compile(prompt: String): CompileResult = CompileResult.Success(template)
}
