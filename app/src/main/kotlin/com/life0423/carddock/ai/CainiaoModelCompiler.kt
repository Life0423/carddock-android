package com.life0423.carddock.ai

import com.life0423.carddock.cainiaoEmptyTemplate
import com.life0423.carddock.core.port.CompileResult
import com.life0423.carddock.core.port.RuleCompiler
import com.life0423.carddock.core.rule.RuleSpec
import com.life0423.carddock.platform.readBounded
import com.life0423.carddock.core.rule.RuleValidator
import java.net.HttpURLConnection
import java.net.URI
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** 只向已配置的 HTTPS 接口发送固定脱敏需求。普通模板和刷新不使用此编译器。 */
internal class CainiaoModelCompiler(private val settings: ModelSettings) : RuleCompiler {
    override suspend fun compile(prompt: String): CompileResult {
        val config = try {
            settings.load()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return CompileResult.Unsupported("模型配置无法读取，请在“我的”重新保存。")
        } ?: return CompileResult.Unsupported("请先在“我的”保存模型配置。")
        return withContext(Dispatchers.IO) {
            val connection = URI(config.endpoint + "/chat/completions").toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer ${config.key}")
            connection.setRequestProperty("Content-Type", "application/json")
            val body = buildJsonObject {
                put("model", config.model)
                put("stream", false)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "user")
                        put("content", """
                            为菜鸟明确的“暂无待取包裹”状态生成卡坞 RuleSpec JSON。只输出 JSON。
                            schemaVersion 固定 1，id 使用短英文标识，sourceId 为 com.cainiao.wireless，
                            targetKey 为 cainiao.pickup.empty。steps 按顺序包含：
                            {"type":"assertTarget","expectedKey":"cainiao.pickup.empty"}
                            {"type":"readField","sourceField":"status","outputKey":"status","label":"待取状态"}
                            {"type":"requireField","outputKey":"status"}
                            无其他字段或步骤。
                        """.trimIndent())
                    }
                }
            }
            try {
                connection.outputStream.use { it.write(body.toString().encodeToByteArray()) }
                val status = connection.responseCode
                if (status !in 200..299) {
                    return@withContext CompileResult.Unsupported("模型服务返回 HTTP $status，请检查配置。")
                }
                val response = connection.inputStream.bufferedReader().use { it.readBounded(32 * 1024) }
                val root = Json.parseToJsonElement(response) as? JsonObject
                val choice = (root?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject
                val message = choice?.get("message") as? JsonObject
                val content = (message?.get("content") as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                compileCainiaoCandidate(content)
            } catch (_: IOException) {
                CompileResult.Unsupported("模型连接失败，请检查服务后重试。")
            } catch (_: IllegalArgumentException) {
                CompileResult.Unsupported("模型响应格式无效，未执行。")
            } finally {
                connection.disconnect()
            }
        }
    }
}

/** 模型边界严格解析并限定来源、目标及步骤。只允许同一已核验状态字段。 */
internal fun compileCainiaoCandidate(content: String): CompileResult {
    val json = content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val rule = try {
        Json.decodeFromString<RuleSpec>(json)
    } catch (_: IllegalArgumentException) {
        return CompileResult.Unsupported("模型候选格式无效，未执行。")
    }
    val baseline = cainiaoEmptyTemplate.rule
    if (RuleValidator.validate(rule) != null || rule.sourceId != baseline.sourceId ||
        rule.targetKey != baseline.targetKey || !rule.id.matches(Regex("[A-Za-z0-9_-]{1,64}")) ||
        rule.steps != baseline.steps
    ) return CompileResult.Unsupported("模型候选超出菜鸟只读范围，未执行。")
    return CompileResult.Success(cainiaoEmptyTemplate.copy(rule = rule))
}
