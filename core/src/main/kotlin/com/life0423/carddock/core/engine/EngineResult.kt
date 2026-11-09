package com.life0423.carddock.core.engine

import com.life0423.carddock.core.model.CardSnapshot

/** currentStep is the number of successfully completed rule steps. */
data class RunProgress(
    val currentStep: Int,
    val totalSteps: Int,
    val message: String,
)

sealed interface EngineResult {
    data class Success(val snapshot: CardSnapshot) : EngineResult
    data class Failure(val code: String, val message: String) : EngineResult
    data class NeedsUser(val message: String) : EngineResult
}
