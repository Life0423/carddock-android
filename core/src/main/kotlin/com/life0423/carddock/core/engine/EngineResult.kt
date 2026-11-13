package com.life0423.carddock.core.engine

import com.life0423.carddock.core.model.CardSnapshot
import com.life0423.carddock.core.model.AppPrice

/**
 * currentStep is the number of successfully completed rule steps.
 * 已经完成的规则步数，不是正在执行的下标。读设备之前会先报 0。
 */
data class RunProgress(
    val currentStep: Int,
    val totalSteps: Int,
    val message: String,
)

/**
 * 一次规则执行的三种结局。只有 [Success] 带新快照，调用方可以据此替换卡片。
 * [Failure] 和 [NeedsUser] 没有快照，上一份已核验结果必须留下。
 */
sealed interface EngineResult {
    data class Success(val snapshot: CardSnapshot, val appPrice: AppPrice? = null) : EngineResult
    /**
     * [code] 给调用方和测试区分原因。会话不把它写入 RunInfo，RunInfo 也没有这个字段。
     * [message] 给界面。inspect 抛出的异常会换成固定文案。DeviceResponse.Failed 的 message 非空白时原样保留，空白时用固定文案。
     */
    data class Failure(val code: String, val message: String) : EngineResult
    /** 引擎到此返回，不再执行后续步骤。会话结束这次运行并放开通道；之后的取消要另写终态。 */
    data class NeedsUser(val message: String) : EngineResult
}
