package com.life0423.carddock.core.engine

import com.life0423.carddock.core.model.CardSnapshot
import com.life0423.carddock.core.model.FieldValue
import com.life0423.carddock.core.port.DeviceGateway
import com.life0423.carddock.core.port.DeviceResponse
import com.life0423.carddock.core.rule.RuleSpec
import com.life0423.carddock.core.rule.RuleStep
import com.life0423.carddock.core.rule.RuleValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Executes a bounded, read-only rule against one coherent device observation.
 * The caller owns queuing, persistence and lifecycle. This class never updates a saved card.
 *
 * 取消原样抛出，由会话决定卡片阶段。其它设备异常收成 DEVICE_ERROR，异常文本不进 [EngineResult.Failure.message]。
 * 成功快照只保留规则读到的字段，时间用 [now]，观察结果里没有单独的时间。
 */
class RuleEngine(
    private val device: DeviceGateway,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun execute(
        rule: RuleSpec,
        onProgress: suspend (RunProgress) -> Unit = {},
    ): EngineResult {
        currentCoroutineContext().ensureActive()
        // Copy the list so a caller cannot change the plan while a device read is suspended.
        val plan = rule.copy(steps = rule.steps.toList())
        RuleValidator.validate(plan)?.let { return EngineResult.Failure(it.code, it.message) }

        onProgress(RunProgress(0, plan.steps.size, "正在读取页面"))
        currentCoroutineContext().ensureActive()
        val response = try {
            device.inspect(plan.sourceId, plan.targetKey)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return EngineResult.Failure("DEVICE_ERROR", "设备读取发生异常，请稍后重试。")
        }
        // 设备若吞掉取消仍返回结果，这里照样抛出，避免把取消写成成功快照。
        currentCoroutineContext().ensureActive()

        val snapshot = when (response) {
            is DeviceResponse.Ready -> response.snapshot
            is DeviceResponse.NeedsUser -> {
                return EngineResult.NeedsUser(response.message.ifBlank { "请先完成所需操作，再继续执行。" })
            }
            is DeviceResponse.Failed -> {
                return EngineResult.Failure(
                    "DEVICE_READ_FAILED",
                    response.message.ifBlank { "未能读取页面，请稍后重试。" },
                )
            }
        }

        if (snapshot.sourceId != plan.sourceId) {
            return EngineResult.Failure("SOURCE_MISMATCH", "页面来源与规则不一致，未提取数据。")
        }
        if (snapshot.targetKey != plan.targetKey) {
            return EngineResult.Failure("TARGET_MISMATCH", "读取到的对象不是指定目标，未更新结果。")
        }

        // Freeze the observed fields only after verifying which source and object they belong to.
        val observedFields = snapshot.fields.toMap()
        val values = linkedMapOf<String, FieldValue>()
        for ((index, step) in plan.steps.withIndex()) {
            currentCoroutineContext().ensureActive()
            val progressMessage = when (step) {
                is RuleStep.AssertTarget -> {
                    if (snapshot.targetKey != step.expectedKey) {
                        return EngineResult.Failure("TARGET_MISMATCH", "目标核对失败，未更新结果。")
                    }
                    "目标核对通过"
                }

                is RuleStep.ReadField -> {
                    val value = observedFields[step.sourceField]
                        ?: return EngineResult.Failure("FIELD_NOT_FOUND", "未找到“${step.label}”，请重新检查来源。")
                    values[step.outputKey] = FieldValue(step.outputKey, step.label, value.trim())
                    "已读取${step.label}"
                }

                is RuleStep.RequireField -> {
                    // 校验已要求该输出来自此前的 ReadField，这里不再做查找失败分支。
                    val value = values.getValue(step.outputKey)
                    if (value.value.isBlank()) {
                        return EngineResult.Failure("REQUIRED_FIELD_EMPTY", "“${value.label}”为空，结果未通过核验。")
                    }
                    "${value.label}核验通过"
                }
            }
            onProgress(RunProgress(index + 1, plan.steps.size, progressMessage))
            currentCoroutineContext().ensureActive()
        }

        return EngineResult.Success(CardSnapshot(fields = values.values.toList(), fetchedAt = now()))
    }
}
