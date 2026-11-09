package com.life0423.carddock.data

import android.content.Context
import androidx.core.util.AtomicFile
import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.port.CardStore
import com.life0423.carddock.core.rule.RuleValidator
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One application-scoped instance owns this private file; the mutex serializes reads and writes. */
class AtomicFileCardStore(context: Context, private val json: Json) : CardStore {
    private val file = AtomicFile(File(context.applicationContext.filesDir, FILE_NAME))
    private val lock = Mutex()

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

    override suspend fun save(cards: List<CardRecord>) = withContext(Dispatchers.IO) {
        lock.withLock {
            currentCoroutineContext().ensureActive()
            validate(cards)
            val bytes = json.encodeToString(StoredCards(STORAGE_VERSION, cards)).toByteArray(Charsets.UTF_8)
            currentCoroutineContext().ensureActive()
            val output = file.startWrite()
            var committed = false
            try {
                output.write(bytes)
                // Detect a disk sync error instead of relying on AtomicFile's internal logging.
                output.fd.sync()
                currentCoroutineContext().ensureActive()
                file.finishWrite(output)
                // AtomicFile logs some rename failures; verify that the committed file is ours.
                val committedBytes = file.openRead().use { it.readBytes() }
                if (!committedBytes.contentEquals(bytes)) throw IOException("Atomic card replacement did not complete")
                committed = true
            } finally {
                // This is synchronous cleanup, so it also runs when the coroutine is cancelled.
                if (!committed) file.failWrite(output)
            }
        }
    }

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
    }
}
