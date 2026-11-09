package com.life0423.carddock.core.rule

data class RuleValidationError(val code: String, val message: String)

/** Validates the complete plan before any device access, including references in later steps. */
object RuleValidator {
    const val SUPPORTED_SCHEMA_VERSION = 1
    const val MAX_STEPS = 32

    fun validate(rule: RuleSpec): RuleValidationError? {
        if (rule.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            return RuleValidationError("UNSUPPORTED_SCHEMA", "当前版本不支持这份规则的格式。")
        }
        if (rule.id.isBlank() || rule.sourceId.isBlank() || rule.targetKey.isBlank()) {
            return invalid("规则标识、来源和目标标识不能为空。")
        }
        if (rule.steps.isEmpty() || rule.steps.size > MAX_STEPS) {
            return invalid("规则需要包含 1 到 $MAX_STEPS 个步骤。")
        }

        var targetAsserted = false
        val outputs = mutableSetOf<String>()

        for (step in rule.steps) {
            when (step) {
                is RuleStep.AssertTarget -> {
                    if (step.expectedKey.isBlank() || step.expectedKey != rule.targetKey) {
                        return invalid("核对步骤中的目标必须与规则目标一致。")
                    }
                    targetAsserted = true
                }

                is RuleStep.ReadField -> {
                    if (!targetAsserted) return invalid("读取字段前必须先核对目标。")
                    if (step.sourceField.isBlank() || step.outputKey.isBlank() || step.label.isBlank()) {
                        return invalid("读取字段的来源名称、输出名称和标签不能为空。")
                    }
                    if (!outputs.add(step.outputKey)) {
                        return invalid("同一条规则不能重复定义相同的输出字段。")
                    }
                }

                is RuleStep.RequireField -> {
                    if (step.outputKey.isBlank() || step.outputKey !in outputs) {
                        return invalid("必填检查只能引用此前已读取的输出字段。")
                    }
                }
            }
        }

        if (!targetAsserted || outputs.isEmpty()) {
            return invalid("规则必须核对目标并读取至少一个字段。")
        }
        return null
    }

    private fun invalid(message: String) = RuleValidationError("INVALID_RULE", message)
}
