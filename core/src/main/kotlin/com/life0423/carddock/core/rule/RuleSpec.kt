package com.life0423.carddock.core.rule

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Versioned, data-only rule format. No scripts, reflection or arbitrary code execution.
 * schemaVersion 当前只接受 1。步骤顺序不在类型里，而在 RuleValidator：先核对目标，再读字段，必填只能引用已读输出。
 */
@Serializable
data class RuleSpec(
    val schemaVersion: Int = 1,
    val id: String,
    val sourceId: String,
    val targetKey: String,
    val steps: List<RuleStep>,
)

/** 持久化用的步骤。序列化名字是稳定协议，不能随 Kotlin 类名变化。 */
@Serializable
sealed interface RuleStep {
    /** 核对观察到的对象键。不读取字段。 */
    @Serializable
    @SerialName("assertTarget")
    data class AssertTarget(val expectedKey: String) : RuleStep

    /** 指定读取 sourceField。引擎去掉首尾空白后写入 outputKey，数据类本身不取设备值。 */
    @Serializable
    @SerialName("readField")
    data class ReadField(
        val sourceField: String,
        val outputKey: String,
        val label: String,
    ) : RuleStep

    /** 要求此前读取的 outputKey 非空白。不能用来读取新字段。 */
    @Serializable
    @SerialName("requireField")
    data class RequireField(val outputKey: String) : RuleStep
}
