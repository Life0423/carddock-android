package com.wangzan101.mika.record

import com.wangzan101.mika.core.record.Redactor
import com.wangzan101.mika.core.record.RunEvent
import kotlinx.serialization.json.*

/** 待取件任务的长期诊断保留动作和页面结构，数字凭据及截图不进入磁盘日志。 */
internal object PickupDiagnostics {
    private val digitToken = Regex("""[A-Za-z0-9０-９]*[0-9０-９][A-Za-z0-9０-９]*(?:[－-][A-Za-z0-9０-９]+)*""")
    fun applies(goal: String) = com.wangzan101.mika.core.agent.LearnGate.privateCodeQuery(goal)
    fun text(value: String) = digitToken.replace(value, Redactor.MASK)

    // 只豁免运行协议自身的关联键。页面标签与工具内嵌的 JSON 字符串递归处理。
    private val protocol = setOf("routeId", "callId", "snapshotId", "ref", "resourceId", "package", "packageName", "currentApp", "place", "type", "tool", "role", "model")
    private fun redact(key: String, value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (k, v) -> redact(k, v) })
        is JsonArray -> JsonArray(value.map { redact(key, it) })
        is JsonPrimitive -> if (!value.isString || key in protocol) value else {
            val raw = value.content
            if (key.endsWith("Json") && raw.startsWith("{")) {
                val parsed = runCatching { RunEvent.json.parseToJsonElement(raw) }.getOrNull()
                JsonPrimitive(parsed?.let { redact("", it).toString() } ?: Redactor.MASK)
            } else JsonPrimitive(text(raw))
        }
    }

    fun event(e: RunEvent): RunEvent {
        val tree = RunEvent.json.encodeToJsonElement(RunEvent.serializer(), e)
        return RunEvent.json.decodeFromJsonElement(RunEvent.serializer(), redact("", tree))
    }
}

/** 本轮核对只借用最近的实际读屏和有效动作，避免持久化凭据或重复持有整份执行记录。 */
internal class PickupLiveEvidence {
    private var read: RunEvent.Observed? = null
    private var result: RunEvent.ToolResult? = null
    private var actionResult: RunEvent.ToolResult? = null
    private var action: RunEvent.ActionTarget? = null
    private var sources: RunEvent.Sources? = null

    @Synchronized fun observe(event: RunEvent) {
        when (event) {
            is RunEvent.Observed -> if (event.kind == "read" && event.inputs != null) read = event
            is RunEvent.ToolResult -> result = event
            is RunEvent.ActionTarget -> if (event.effective) {
                action = event
                actionResult = result?.takeIf { it.callId != null && it.callId == event.callId && it.tool == event.tool }
            }
            is RunEvent.Sources -> sources = event
            else -> Unit
        }
    }

    @Synchronized fun evidence(): List<RunEvent> = listOfNotNull(actionResult, action, read, sources)
}
