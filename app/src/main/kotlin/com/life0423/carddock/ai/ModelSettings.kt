package com.life0423.carddock.ai

import android.content.Context
import com.life0423.carddock.data.PrivateCipherFile
import java.io.File
import java.net.URI
import java.security.KeyStore
import kotlinx.coroutines.Dispatchers
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
internal class ModelSettings(private val context: Context) {
    private val bridge = context.getSharedPreferences("carddock_model_bridge", Context.MODE_PRIVATE)

    /** 升级时移交旧配置，之后保留用户在 Mika 内自行选择的主模型。 */
    suspend fun migrateToMika() = withContext(Dispatchers.IO) {
        if (!bridge.contains("provider")) load()?.let(::configureMika)
    }

    private fun configureMika(config: ModelConfig) {
        val store = com.wangzan101.mika.settings.ProviderStore.get(context)
        val provider = store.connect("卡坞模型", com.wangzan101.mika.settings.ProviderConfig.Kind.OpenAiCompat,
            config.endpoint, config.key, emptyList(), untested = true)
        val caps = com.wangzan101.mika.settings.ProviderPresets.forBaseUrl(config.endpoint)?.capabilities
            ?: com.wangzan101.mika.core.model.Capabilities(true, false, 64_000, false)
        check(store.use(com.wangzan101.mika.core.model.ModelRole.Main, provider.id, config.model, caps))
        bridge.getString("provider", null)?.takeIf { it != provider.id }?.let(store::removeProvider)
        bridge.edit().putString("provider", provider.id).apply()
    }

    private val file = PrivateCipherFile(File(context.filesDir, "model-v1.enc"), ALIAS)

    suspend fun load(): ModelConfig? = withContext(Dispatchers.IO) {
        file.read()?.let { Json.decodeFromString<ModelConfig>(it) }
    }

    suspend fun save(endpoint: String, model: String, key: String): ModelSummary = withContext(Dispatchers.IO) {
        val address = URI(endpoint.trim().trimEnd('/'))
        require(address.scheme == "https" && address.host != null && address.userInfo == null &&
            address.query == null && address.fragment == null && model.isNotBlank() && key.isNotBlank() &&
            key.none { it.isWhitespace() || it.isISOControl() })
        val config = ModelConfig(address.toASCIIString(), model.trim(), key)
        file.write(Json.encodeToString(ModelConfig.serializer(), config))
        configureMika(config)
        ModelSummary(config.endpoint, config.model)
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(ALIAS) }
        file.delete()
        bridge.getString("provider", null)?.let(com.wangzan101.mika.settings.ProviderStore.get(context)::removeProvider)
        bridge.edit().remove("provider").apply()
    }

    private companion object { const val ALIAS = "carddock.model.v1" }
}
