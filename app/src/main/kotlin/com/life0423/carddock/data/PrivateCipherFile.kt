package com.life0423.carddock.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 模型配置和登录会话共用 AES-GCM 文件格式，各自使用独立的 Android Keystore 密钥。调用方负责 IO 调度。 */
internal class PrivateCipherFile(path: File, private val alias: String, private val key: (Boolean) -> SecretKey = { create ->
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    store.getKey(alias, null) as? SecretKey ?: if (create) KeyGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore",
    ).run {
        init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        generateKey()
    } else throw IOException("设备密钥不可用")
}) {
    private val file = AtomicFile(path)

    fun read(): String? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val bytes = file.readFully()
        if (bytes.size <= 12) throw IOException("加密文件不完整")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes, 0, 12))
        return cipher.doFinal(bytes, 12, bytes.size - 12).decodeToString()
    }

    fun write(value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        val encrypted = cipher.iv + cipher.doFinal(value.encodeToByteArray())
        val output = file.startWrite()
        try {
            output.write(encrypted)
            file.finishWrite(output)
        } catch (error: IOException) {
            file.failWrite(output)
            throw error
        }
        if (!file.readFully().contentEquals(encrypted)) throw IOException("加密文件写入失败")
    }

    fun delete() = file.delete()
}
