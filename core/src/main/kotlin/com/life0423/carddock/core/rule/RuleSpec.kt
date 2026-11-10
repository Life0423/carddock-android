package com.life0423.carddock.core.rule

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Versioned, data-only rule format. No scripts, reflection or arbitrary code execution. */
@Serializable
data class RuleSpec(
    val schemaVersion: Int = 1,
    val id: String,
    val sourceId: String,
    val targetKey: String,
    val steps: List<RuleStep>,
)

@Serializable
sealed interface RuleStep {
    @Serializable
    @SerialName("assertTarget")
    data class AssertTarget(val expectedKey: String) : RuleStep

    @Serializable
    @SerialName("readField")
    data class ReadField(
        val sourceField: String,
        val outputKey: String,
        val label: String,
    ) : RuleStep

    @Serializable
    @SerialName("requireField")
    data class RequireField(val outputKey: String) : RuleStep
}
