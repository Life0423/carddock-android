package com.life0423.carddock.ai

import android.content.Context
import com.life0423.carddock.data.PrivateCipherFile
import java.io.File
import java.net.URI
import java.security.KeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** UI 只读地址与模型名。密钥仅在保存和发送模型请求时存在。 */
internal data class ModelSummary(val endpoint: String, val model: String)

@Serializable
internal class ModelConfig(val endpoint: String, val model: String, val key: String) {
    override fun toString(): String = "ModelConfig(已隐藏)"
}

/** 使用 Android Keystore 的 AES-GCM 密钥，加密整份配置并原子写入私有目录。 */
internal class ModelSettings(context: Context) {
    private val file = PrivateCipherFile(File(context.filesDir, "model-v1.enc"), ALIAS)

    suspend fun load(): ModelConfig? = withContext(Dispatchers.IO) {
        access.withLock { file.read()?.let { Json.decodeFromString<ModelConfig>(it) } }
    }

    suspend fun save(endpoint: String, model: String, key: String): ModelSummary = withContext(Dispatchers.IO) {
        val address = URI.create(endpoint.trim().trimEnd('/'))
        require(address.scheme == "https" && address.host != null && address.userInfo == null &&
            address.query == null && address.fragment == null && model.isNotBlank() && key.isNotBlank() &&
            key.none { it.isWhitespace() || it.isISOControl() })
        val config = ModelConfig(address.toASCIIString(), model.trim(), key)
        access.withLock { file.write(Json.encodeToString(ModelConfig.serializer(), config)) }
        ModelSummary(config.endpoint, config.model)
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        access.withLock {
            file.delete()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(ALIAS) }
        }
    }

    private companion object {
        const val ALIAS = "carddock.model.v1"
        // 助手读取配置时可能正好切换到设置页。读取不可移除写入中的 AtomicFile 临时文件。
        val access = Mutex()
    }
}
