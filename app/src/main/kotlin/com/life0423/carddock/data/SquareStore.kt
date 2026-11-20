package com.life0423.carddock.data

import android.content.Context
import com.life0423.carddock.cainiaoEmptyTemplate
import com.life0423.carddock.core.model.DisplayField
import com.life0423.carddock.core.model.TemplateDefinition
import com.life0423.carddock.core.rule.RuleStep
import kotlinx.serialization.json.encodeToJsonElement
import com.life0423.carddock.jdTemplates
import com.life0423.carddock.meituanStatusTemplate
import com.life0423.carddock.meituanTemplate
import java.io.IOException
import java.io.File
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val publicCapabilities =
    (listOf(cainiaoEmptyTemplate, meituanTemplate, meituanStatusTemplate) + jdTemplates).associateBy { it.id }

/** 公开模板只携带能力代号与公开说明。规则在本机按白名单还原。 */
@Serializable
internal data class SquareEntry(
    val id: String,
    val author: String,
    val version: Int,
    val title: String,
    val description: String,
    val capability: String,
    val downloads: Int,
    val display: List<DisplayField> = emptyList(),
) {
    fun definition(): TemplateDefinition = publicCapabilities.getValue(capability)
        .copy(id = id, title = title, description = description, revision = version, display = display)
}

internal data class SquareState(
    val available: Boolean = false,
    val entries: List<SquareEntry> = emptyList(),
    val publications: List<SquareEntry> = emptyList(),
    val favorites: List<SquareEntry> = emptyList(),
    val installed: List<SquareEntry> = emptyList(),
    val account: String? = null,
    val signedIn: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
)

/** 私有会话只写加密文件，界面状态只包含展示用账号名。 */
@Serializable
private class SquareSession(val name: String, val id: String, val legacy: Boolean, val token: String)
private class SessionExpired : IOException("登录已过期，请重新登录。")

internal class SquareStore(context: Context, url: String, private val scope: CoroutineScope,
    private val credentials: PrivateCipherFile = PrivateCipherFile(File(context.noBackupFilesDir,
        "square-session-" + MessageDigest.getInstance("SHA-256").digest(url.trimEnd('/').encodeToByteArray())
            .joinToString("") { "%02x".format(it) } + ".enc"), "carddock.square.v1"),
) {
    private val base = url.trimEnd('/')
    private val preferences = context.getSharedPreferences("square-v1", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(SquareState(available = base.isNotEmpty()))
    val state = mutableState.asStateFlow()
    private var session: SquareSession? = null
    private val token get() = session?.token
    private var query = ""
    private var latest = false

    init {
        operate {
            val saved = withContext(Dispatchers.IO) { credentials.read() } ?: return@operate
            changeProfile(Json.decodeFromString<SquareSession>(saved))
            // 有网络时立即确认撤销状态；短暂离线仍可访问该会话的本机副本。
            request("/account")
        }
    }

    fun refresh(text: String = query, newest: Boolean = latest) = operate {
        query = text.trim().take(80)
        latest = newest
        loadEntries()
    }

    fun signIn(name: String, password: String, register: Boolean) = operate {
        val body = buildJsonObject { put("name", name.trim()); put("password", password) }
        saveSession(request(if (register) "/account/register" else "/account/login", body.toString(), credential = null))
    }

    fun changePassword(current: String, password: String) = operate {
        val body = buildJsonObject { put("current", current); put("password", password) }
        saveSession(request("/account/password", body.toString()))
        mutableState.value = state.value.copy(message = "密码已更新")
    }

    fun signOut() = operate {
        val previous = token ?: return@operate
        clearSession()
        try {
            request("/account/logout", "{}", credential = previous)
        } catch (_: SessionExpired) {
            // 服务端此前已撤销该会话。
        } catch (_: IOException) {
            mutableState.value = state.value.copy(message = OFFLINE_LOGOUT)
        }
    }

    fun loadPublications() = operate {
        val entries = Json.decodeFromString<List<SquareEntry>>(request("/account/templates"))
        mutableState.value = state.value.copy(publications = entries.map { it.validated() })
    }

    private suspend fun saveSession(response: String) {
        val next = Json.decodeFromString<SquareSession>(response)
        withContext(Dispatchers.IO) { credentials.write(response) }
        changeProfile(next)
    }

    private suspend fun clearSession() {
        changeProfile(null)
        withContext(Dispatchers.IO) { credentials.delete() }
    }

    fun canPublish(template: TemplateDefinition): Boolean = capability(template) != null

    fun publish(template: TemplateDefinition) = operate {
        val capability = capability(template) ?: throw IOException("该模板暂不支持公开发布。")
        val content = buildJsonObject {
            put("localId", template.id)
            put("version", template.revision)
            put("title", template.title)
            put("description", template.description)
            put("capability", capability)
            if (template.display.isNotEmpty()) put("display", Json.encodeToJsonElement(template.display))
        }
        request("/templates", body = content.toString())
        mutableState.value = state.value.copy(message = "模板已发布")
        loadEntries()
    }

    fun unpublish(entry: SquareEntry) = operate {
        request("/templates/${entry.id}/unpublish", body = "{}")
        mutableState.value = state.value.copy(message = "模板已下架", publications = state.value.publications.filterNot { it.id == entry.id })
        loadEntries()
    }

    fun toggleFavorite(entry: SquareEntry) {
        if (state.value.busy || session == null) return
        val existing = state.value.favorites
        val next = if (existing.any { it.id == entry.id }) existing.filterNot { it.id == entry.id } else existing + entry
        preferences.edit().putString(profileKey("favorites"), Json.encodeToString(next)).apply()
        mutableState.value = state.value.copy(favorites = next)
    }

    fun download(entry: SquareEntry) = operate {
        if (token == null) throw IOException("请先登录再下载。")
        // 由服务端一次确认上架状态、登记下载并返回用于安装的版本。
        val response = request("/templates/${entry.id}/download", body = "{}")
        val latestEntry = Json.decodeFromString<SquareEntry>(response).validated()
        if (latestEntry.id != entry.id) throw IOException("广场返回了错误的模板。")
        val installed = state.value.installed
        val previous = installed.firstOrNull { it.id == entry.id }
        if (previous == null || previous.version < latestEntry.version) {
            val next = installed.filterNot { it.id == entry.id } + latestEntry
            val key = profileKey("installed")
            val saved = withContext(Dispatchers.IO) {
                preferences.edit().putString(key, Json.encodeToString(next)).commit()
            }
            if (!saved) throw IOException("保存到本机失败，请重试。")
            mutableState.value = state.value.copy(installed = next)
        }
        mutableState.value = state.value.copy(message = "已加入「模板 → 已下载」")
        loadEntries()
    }

    private fun changeProfile(next: SquareSession?) {
        session = next
        mutableState.value = state.value.copy(
            account = next?.name, signedIn = next != null, publications = emptyList(),
            favorites = if (next == null) emptyList() else readLocal("favorites"),
            installed = if (next == null) emptyList() else readLocal("installed"),
        )
    }

    // 管理员已迁移的旧账号延用原命名空间，新注册账号使用服务端不可变 ID。
    private fun profileKey(list: String): String {
        val owner = requireNotNull(session)
        return (if (owner.legacy) owner.name else "v2/${owner.id}") + ":$list"
    }

    private fun readLocal(list: String): List<SquareEntry> =
        preferences.getString(profileKey(list), null)?.let { Json.decodeFromString<List<SquareEntry>>(it) }.orEmpty()

    private fun capability(template: TemplateDefinition): String? = publicCapabilities.entries.firstOrNull { (_, standard) ->
        template.pickupEntry == null && template.requiresOrderNumber == standard.requiresOrderNumber &&
            template.rule.copy(id = standard.rule.id) == standard.rule
    }?.key

    private fun operate(block: suspend () -> Unit) {
        if (!state.value.available || state.value.busy) return
        mutableState.value = state.value.copy(busy = true, message = null)
        scope.launch {
            try {
                block()
            } catch (_: SessionExpired) {
                clearSession()
                reportFailure("登录已过期，请重新登录。")
            } catch (_: GeneralSecurityException) {
                reportFailure("无法读取或保存本机登录凭据，请重新登录。")
            } catch (error: IOException) {
                reportFailure(error.message ?: "连接广场失败，请重试。")
            } catch (_: SerializationException) {
                reportFailure("广场返回了无效数据。")
            } finally {
                mutableState.value = state.value.copy(busy = false)
            }
        }
    }

    /** 已完成的操作在列表更新失败时仍保留成功提示。 */
    private fun reportFailure(message: String) {
        val current = state.value
        mutableState.value = current.copy(
            message = current.message?.let { "$it，广场列表暂未更新。" } ?: message,
        )
    }

    private suspend fun loadEntries() {
        val term = URLEncoder.encode(query, "UTF-8")
        val sort = if (latest) "latest" else "rank"
        val entries = Json.decodeFromString<List<SquareEntry>>(request("/templates?sort=$sort&q=$term"))
        mutableState.value = state.value.copy(entries = entries.map { it.validated() })
    }

    private fun SquareEntry.validated(): SquareEntry {
        if (capability !in publicCapabilities || version < 1 || downloads < 0 ||
            !id.matches(Regex("[a-f0-9-]{36}")) || title.isBlank() || title.length > 40 || description.length > 160
        ) throw IOException("广场返回了无效模板。")
        val keys = publicCapabilities.getValue(capability).rule.steps.filterIsInstance<RuleStep.ReadField>().map { it.outputKey }
        if (display.map { it.key }.distinct().size != display.size ||
            display.any { it.key !in keys || it.label.isBlank() || it.label.length > 16 }
        ) throw IOException("广场返回了无效的展示字段。")
        return this
    }

    companion object {
        const val OFFLINE_LOGOUT = "已退出本机，服务端尚未确认撤销。"
    }

    private suspend fun request(path: String, body: String? = null, credential: String? = token): String =
        withContext(Dispatchers.IO) {
            val connection = URI.create(base + path).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("X-CardDock-Display", "1")
            try {
                credential?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                if (connection.responseCode == 401 && credential != null) throw SessionExpired()
                if (connection.responseCode !in 200..299) {
                    val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    val message = error?.let {
                        runCatching { Json.parseToJsonElement(it).jsonObject["message"]?.jsonPrimitive?.content }.getOrNull()
                    }
                    throw IOException(message ?: "广场请求失败，请重试。")
                }
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
}
