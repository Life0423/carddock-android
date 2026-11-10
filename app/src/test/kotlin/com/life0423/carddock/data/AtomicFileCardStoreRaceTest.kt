package com.life0423.carddock.data

import android.app.Application
import android.content.Context
import androidx.core.util.AtomicFile
import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.demo.TemplateCatalog
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 两个 store 写同一个 cards-v1.json。
 * A 在 finishWrite 已经换成自己的字节、还没做回读校验时，B 开始写。
 * 两边各有一把锁时，B 能在 A 校验前换掉文件，A 会校验失败或留下对不上的内容。
 * 锁已经按路径共用时，B 进不了这段临界区；这时再等 B 写完就会死锁，所以只在两把锁不是同一把时才等。
 * 这个判断只用来排交错，断言仍是两次写入都成功，且读回是其中一份完整数据。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AtomicFileCardStoreRaceTest {
    private lateinit var context: Context
    private lateinit var base: File
    private val json = Json { encodeDefaults = true }
    private val renamedA = CountDownLatch(1)
    private val startedB = CountDownLatch(1)
    private val finishedB = CountDownLatch(1)

    @Before
    fun clearPrivateStore() {
        context = RuntimeEnvironment.getApplication()
        base = File(context.filesDir, "cards-v1.json")
        base.deleteRecursively()
        File(base.path + ".new").delete()
        File(base.path + ".bak").delete()
        AtomicFile(base).delete()
    }

    @After
    fun removeTestFiles() {
        base.deleteRecursively()
        File(base.path + ".new").delete()
        File(base.path + ".bak").delete()
        AtomicFile(base).delete()
    }

    @Test
    fun twoInstancesWritingTheSameFileKeepACompleteCollection() = runBlocking {
        val storeA = AtomicFileCardStore(context, json)
        val storeB = AtomicFileCardStore(context, json)
        install(storeA, Gate(base, "A", storeA, storeB))
        install(storeB, Gate(base, "B", storeA, storeB))
        val cardsA = listOf(card("writer-a"))
        val cardsB = listOf(card("writer-b"))

        val first = async(Dispatchers.IO) { runCatching { storeA.save(cardsA) } }
        renamedA.await()
        val second = async(Dispatchers.IO) {
            startedB.countDown()
            try {
                runCatching { storeB.save(cardsB) }
            } finally {
                finishedB.countDown()
            }
        }

        val resultA = first.await()
        val resultB = second.await()
        assertTrue(
            "两个实例并发写同一文件不应失败，A 实际: ${resultA.exceptionOrNull()}",
            resultA.isSuccess,
        )
        assertTrue(
            "两个实例并发写同一文件不应失败，B 实际: ${resultB.exceptionOrNull()}",
            resultB.isSuccess,
        )
        val loaded = storeA.load()
        assertTrue(
            "读回必须是某一次完整写入，实际 ${loaded?.map { it.id }}",
            loaded == cardsA || loaded == cardsB,
        )
        assertEquals(loaded, storeB.load())
    }

    private fun install(store: AtomicFileCardStore, gate: Gate) {
        AtomicFileCardStore::class.java.getDeclaredField("file").apply {
            isAccessible = true
            set(store, gate)
        }
    }

    private fun mutexOf(store: AtomicFileCardStore): Mutex {
        val field = AtomicFileCardStore::class.java.getDeclaredField("lock")
        field.isAccessible = true
        return field.get(store) as Mutex
    }

    private fun card(id: String): CardRecord {
        val template = TemplateCatalog.templates.first()
        return CardRecord(id, template.title, template.kind, template.rule)
    }

    private inner class Gate(
        base: File,
        private val role: String,
        private val storeA: AtomicFileCardStore,
        private val storeB: AtomicFileCardStore,
    ) : AtomicFile(base) {
        override fun finishWrite(stream: FileOutputStream?) {
            super.finishWrite(stream)
            if (role != "A") return
            renamedA.countDown()
            startedB.await()
            val sameLock = mutexOf(storeA) === mutexOf(storeB)
            // B 已经出发。同一把锁被 A 占着时，B 只能等 A 离开临界区，这里不能反过来等 B。
            if (sameLock && mutexOf(storeA).isLocked) return
            finishedB.await()
        }
    }
}
