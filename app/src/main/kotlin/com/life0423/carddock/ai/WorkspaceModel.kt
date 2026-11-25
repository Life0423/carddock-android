package com.life0423.carddock.ai

import android.content.Context
import com.life0423.carddock.core.model.CardRecord
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.GeneralSecurityException
import java.security.ProviderException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** 可显示给用户的模型失败。服务返回的正文、配置和密钥不写入提示。 */
internal class WorkspaceModelException(message: String) : Exception(message)

@Serializable
internal data class WorkspaceProposal(val name: String, val cardIds: List<String>)

@Serializable
private data class GroupAnswer(val name: String, val cards: List<Int>)

@Serializable
private data class MetricAnswer(val metrics: List<Int>)

/** 字段键相同但含义不同的结果，如待取状态与配送状态，分开对比。 */
internal data class ComparisonMetric(val key: String, val label: String) {
    val id: String get() = "${key.length}:$key$label"
    fun value(card: CardRecord): String? =
        card.snapshot?.fields?.firstOrNull { it.key == key && it.label == label }?.value?.takeUnless(String::isBlank)
}

internal fun comparisonMetrics(cards: List<CardRecord>): List<ComparisonMetric> = cards
    .flatMap { it.snapshot?.fields.orEmpty() }
    .map { ComparisonMetric(it.key, it.label) }
    .distinct()

/** 把会话内序号重新绑定到本机卡片，拒绝重复、越界或只有一张卡的方案。 */
internal fun decodeWorkspaceProposal(reply: String, cardIds: List<String>): WorkspaceProposal? {
    val answer = Json.decodeFromString<GroupAnswer>(modelJson(reply))
    val name = answer.name.trim()
    if (name.length !in 1..30 || answer.cards.size < 2 || answer.cards.distinct().size != answer.cards.size) return null
    val ids = answer.cards.map { cardIds.getOrNull(it - 1) ?: return null }
    return WorkspaceProposal(name, ids)
}

/** 模型只能选择已有指标；界面随后从卡片快照读值。 */
internal fun decodeMetricSelection(reply: String, metrics: List<ComparisonMetric>): List<String> {
    val indexes = Json.decodeFromString<MetricAnswer>(modelJson(reply)).metrics
    return indexes.mapNotNull { metrics.getOrNull(it - 1)?.id }.distinct().take(8)
}

private val privateReferences = Regex("""(?i)https?://\S+|(?<!\d)\d(?:[\p{Cf} \-]?\d){10,}""")

private val shortPrivateReferences = Regex("""(?i)((?:订单尾号|订单号码|订单号|路由编号|尾号|\boid\b)\s*[:：#]?\s*)\d{4,10}(?!\d)""")

private val pickupReferences = Regex("""(?i)(取件码\s*[:：#]?\s*)(?=[a-z0-9－-]{4,20}(?![a-z0-9－-]))(?=[a-z0-9－-]*\d)[a-z0-9]+(?:[－-][a-z0-9]+)*""")

internal fun modelText(value: String): String = value.replace(privateReferences, "（已隐藏）")
    .replace(shortPrivateReferences) { "${it.groupValues[1]}（已隐藏）" }
    .replace(pickupReferences) { "${it.groupValues[1]}（已隐藏）" }

private fun modelJson(reply: String): String =
    reply.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

/** 复用 dev 的 OpenAI 兼容连接方式。界面只在用户发起请求后发送任务描述和所需目录。 */
internal suspend fun requestWorkspaceModel(context: Context, instruction: String, input: String): String {
    val config = try {
        ModelSettings(context).load()
    } catch (_: IOException) {
        throw WorkspaceModelException("模型配置无法读取，请在「我的」重新保存。")
    } catch (_: IllegalArgumentException) {
        throw WorkspaceModelException("模型配置无法读取，请在「我的」重新保存。")
    } catch (_: GeneralSecurityException) {
        throw WorkspaceModelException("设备密钥不可用，请在「我的」重新保存。")
    } catch (_: ProviderException) {
        throw WorkspaceModelException("设备密钥不可用，请在「我的」重新保存。")
    } ?: throw WorkspaceModelException("请先在「我的」配置模型。")

    return withContext(Dispatchers.IO) {
        try {
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
                    addJsonObject { put("role", "system"); put("content", instruction) }
                    addJsonObject { put("role", "user"); put("content", input) }
                }
            }
            try {
                connection.outputStream.use { it.write(body.toString().encodeToByteArray()) }
                val status = connection.responseCode
                if (status !in 200..299) throw WorkspaceModelException("模型服务返回 HTTP $status，请检查配置。")
                val response = connection.inputStream.bufferedReader().use { reader ->
                    val chars = CharArray(4096)
                    buildString {
                        while (true) {
                            val count = reader.read(chars)
                            if (count == -1) break
                            if (length + count > 64 * 1024) throw IOException("响应过长")
                            append(chars, 0, count)
                        }
                    }
                }
                val root = Json.parseToJsonElement(response) as? JsonObject
                val choice = (root?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject
                val message = choice?.get("message") as? JsonObject
                (message?.get("content") as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content
                    ?: throw WorkspaceModelException("模型未返回可用内容，请重试。")
            } finally {
                connection.disconnect()
            }
        } catch (_: IOException) {
            throw WorkspaceModelException("模型连接失败，请检查服务后重试。")
        } catch (_: IllegalArgumentException) {
            throw WorkspaceModelException("模型响应格式无效，请重试。")
        }
    }
}
