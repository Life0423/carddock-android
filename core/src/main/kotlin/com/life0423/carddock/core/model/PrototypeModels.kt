package com.life0423.carddock.core.model

import com.life0423.carddock.core.rule.RuleSpec
import kotlinx.serialization.Serializable

@Serializable
enum class TemplateKind { EXPRESS, DELIVERY, TRAVEL }

@Serializable
data class FieldValue(
    val key: String,
    val label: String,
    val value: String,
)

/** Only verified results become snapshots; a failed refresh must retain the previous one. */
@Serializable
data class CardSnapshot(
    val fields: List<FieldValue>,
    val fetchedAt: Long,
    val sourceLabel: String = "模拟设备 · 演示数据",
)

@Serializable
enum class RunPhase {
    IDLE,
    QUEUED,
    RUNNING,
    NEEDS_USER,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    INTERRUPTED,
}

@Serializable
data class RunInfo(
    val id: String,
    val phase: RunPhase,
    val message: String = "",
    val currentStep: Int = 0,
    val totalSteps: Int = 0,
    val startedAt: Long = 0,
    val endedAt: Long? = null,
)

@Serializable
data class CardRecord(
    val id: String,
    val title: String,
    val kind: TemplateKind,
    val rule: RuleSpec,
    val snapshot: CardSnapshot? = null,
    val run: RunInfo? = null,
)

@Serializable
data class TemplateDefinition(
    val id: String,
    val title: String,
    val description: String,
    val kind: TemplateKind,
    val examplePrompt: String,
    val rule: RuleSpec,
)
