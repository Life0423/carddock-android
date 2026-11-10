package com.life0423.carddock.core.engine

import com.life0423.carddock.core.model.CardSnapshot

/** currentStep is the number of successfully completed rule steps. */
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
    data class Success(val snapshot: CardSnapshot) : EngineResult
    /** [code] 给存储和测试区分原因，[message] 给界面，不包含设备异常原文。 */
    data class Failure(val code: String, val message: String) : EngineResult
    /** 引擎到此返回，不再执行后续步骤。会话结束这次运行并放开通道；之后的取消要另写终态。 */
    data class NeedsUser(val message: String) : EngineResult
}
