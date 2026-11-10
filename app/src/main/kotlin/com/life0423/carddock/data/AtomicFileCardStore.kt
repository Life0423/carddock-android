package com.life0423.carddock.data

import android.content.Context
import androidx.core.util.AtomicFile
import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.port.CardStore
import com.life0423.carddock.core.rule.RuleValidator
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 把卡片集合写到应用私有目录的 cards-v1.json。
 * 每个 AppContainer 都会 new 一个实例。实例自己的锁挡不住另一个实例，
 * finishWrite 之后的字节校验会读到对方刚换上的内容。
 * 所以同一进程里按规范化路径共用一把锁，读和写都走它。
 * load 也要占这把锁：openRead 在正式文件还在时会删掉未完成的 .new。
 */
class AtomicFileCardStore(context: Context, private val json: Json) : CardStore {
    private val file = AtomicFile(File(context.applicationContext.filesDir, FILE_NAME))
    private val lock = lockFor(file.baseFile)

    /**
     * 没有正式文件也没有 .bak 时返回 null，表示第一次启动。
     * 其它读失败或版本不对直接抛出，调用方不得当成空集合。
     */
    override suspend fun load(): List<CardRecord>? = withContext(Dispatchers.IO) {
        lock.withLock {
            currentCoroutineContext().ensureActive()
            val text = try {
                file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (missing: FileNotFoundException) {
                // openRead gets the first chance to recover a previous AtomicFile backup.
                if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@withLock null
                throw missing
            }
            currentCoroutineContext().ensureActive()
            val stored = json.decodeFromString<StoredCards>(text)
            if (stored.version != STORAGE_VERSION) throw IOException("Unsupported card storage version: ${stored.version}")
            validate(stored.cards)
            stored.cards
        }
    }

    /**
     * 校验通过后才写。取消或写失败走 failWrite，只丢掉未完成的 .new。
     * finishWrite 之后只确认字节，失败只抛异常，不回滚已经换上的正式文件。
     */
    override suspend fun save(cards: List<CardRecord>) = withContext(Dispatchers.IO) {
        lock.withLock {
            currentCoroutineContext().ensureActive()
            validate(cards)
            val bytes = json.encodeToString(StoredCards(STORAGE_VERSION, cards)).toByteArray(Charsets.UTF_8)
            currentCoroutineContext().ensureActive()
            val output = file.startWrite()
            try {
                output.write(bytes)
                // 同步失败发生在替换正式文件之前，旧内容还在。
                output.fd.sync()
                currentCoroutineContext().ensureActive()
                file.finishWrite(output)
            } catch (cancelled: CancellationException) {
                file.failWrite(output)
                throw cancelled
            } catch (error: Exception) {
                file.failWrite(output)
                throw error
            }
            // finishWrite 返回后不再 failWrite。1.15.0 的 failWrite 只删除 .new，不删除正式文件，也不能把已换上的内容滚回去。
            if (!baseFileHas(bytes)) {
                throw IOException("Atomic card replacement did not complete")
            }
        }
    }

    /** 仅在正式文件可读且字节一致时确认成功。false 可能是未提交，也可能只是无法确认，不能据此回滚。 */
    private fun baseFileHas(bytes: ByteArray): Boolean {
        val base = file.baseFile
        if (!base.isFile) return false
        return try {
            base.readBytes().contentEquals(bytes)
        } catch (_: IOException) {
            false
        }
    }

    /** 空白 id、空标题、重复 id 或规则不合法都拒绝整份集合，避免坏数据进内存后再被写回去。 */
    private fun validate(cards: List<CardRecord>) {
        val ids = mutableSetOf<String>()
        for (card in cards) {
            if (card.id.isBlank() || card.title.isBlank() || !ids.add(card.id)) {
                throw IOException("Invalid or duplicate card identity")
            }
            RuleValidator.validate(card.rule)?.let { throw IOException("Invalid stored rule: ${it.code}") }
        }
    }

    @Serializable
    private data class StoredCards(val version: Int, val cards: List<CardRecord>)

    private companion object {
        const val FILE_NAME = "cards-v1.json"
        const val STORAGE_VERSION = 1
        private val locks = ConcurrentHashMap<String, Mutex>()

        /** 先规范化路径，同一文件不会因为 ./ 或符号链接拿到两把锁。 */
        private fun lockFor(base: File): Mutex {
            val key = try {
                base.canonicalPath
            } catch (_: IOException) {
                base.absolutePath
            }
            return locks.computeIfAbsent(key) { Mutex() }
        }
    }
}
