package com.life0423.carddock.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
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
internal class ModelSettings(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "model-v1.enc"))

    suspend fun load(): ModelConfig? = withContext(Dispatchers.IO) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@withContext null
        val bytes = file.readFully()
        require(bytes.size > IV_SIZE)
        val key = keyStore().getKey(ALIAS, null) as? SecretKey ?: throw IOException("Model key unavailable")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, IV_SIZE)))
        val plaintext = cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE)
        Json.decodeFromString<ModelConfig>(plaintext.decodeToString())
    }

    suspend fun save(endpoint: String, model: String, key: String): ModelSummary = withContext(Dispatchers.IO) {
        val address = URI(endpoint.trim().trimEnd('/'))
        require(address.scheme == "https" && address.host != null && address.userInfo == null &&
            address.query == null && address.fragment == null && model.isNotBlank() && key.isNotBlank() &&
            key.none { it.isWhitespace() || it.isISOControl() })
        val store = keyStore()
        val secret = store.getKey(ALIAS, null) as? SecretKey ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore",
        ).run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
        val config = ModelConfig(address.toASCIIString(), model.trim(), key)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret)
        val encrypted = cipher.iv + cipher.doFinal(Json.encodeToString(ModelConfig.serializer(), config).encodeToByteArray())
        val output = file.startWrite()
        try {
            output.write(encrypted)
            file.finishWrite(output)
        } catch (error: IOException) {
            file.failWrite(output)
            throw error
        }
        if (!file.readFully().contentEquals(encrypted)) throw IOException("Model settings write failed")
        ModelSummary(config.endpoint, config.model)
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        keyStore().deleteEntry(ALIAS)
        file.delete()
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private companion object {
        const val ALIAS = "carddock.model.v1"
        const val IV_SIZE = 12
    }
}
