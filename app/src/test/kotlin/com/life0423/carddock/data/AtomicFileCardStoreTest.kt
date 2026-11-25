package com.life0423.carddock.data

import android.app.Application
import android.content.Context
import androidx.core.util.AtomicFile
import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.model.CompareGroup
import com.life0423.carddock.core.model.SpaceRecords
import com.life0423.carddock.core.model.CardSnapshot
import com.life0423.carddock.core.model.FieldValue
import com.life0423.carddock.core.model.PageTarget
import com.life0423.carddock.demo.TemplateCatalog
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 不启动 CardDockApplication。它会再建一个写同一 cards-v1.json 的 store，
 * 两把锁互不相让，读回校验会偶发失败。入口冒烟仍用真实 Application。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AtomicFileCardStoreTest {
    private lateinit var context: Context
    private lateinit var baseFile: File
    private val json = Json { encodeDefaults = true }

    @Before
    fun clearPrivateStore() {
        context = RuntimeEnvironment.getApplication()
        baseFile = File(context.filesDir, "cards-v1.json")
        AtomicFile(baseFile).delete()
    }

    @After
    fun removeTestFiles() {
        baseFile.deleteRecursively()
        AtomicFile(baseFile).delete()
    }

    @Test
    fun nativeRuleAndSourceSurviveAtomicFileReload() = runBlocking {
        val template = com.life0423.carddock.cainiaoEmptyTemplate
        val card = CardRecord("native", template.title, template.kind, template.rule,
            CardSnapshot(listOf(FieldValue("status", "待取状态", "暂无待取包裹")), 123L, "菜鸟 · 当前页面"))
        newStore().save(listOf(card))
        assertEquals(listOf(card), newStore().load())
    }

    @Test
    fun firstLaunchDiffersFromAUserSavedEmptyCollection() = runBlocking {
        val store = newStore()
        assertNull(store.load())

        store.save(emptyList())

        assertEquals(emptyList<CardRecord>(), newStore().load())
        assertEquals("3", json.parseToJsonElement(baseFile.readText()).jsonObject.getValue("version").jsonPrimitive.content)
    }

    @Test
    fun cardRuleAndVerifiedSnapshotSurviveStoreRecreation() = runBlocking {
        val cards = listOf(exampleCard())

        newStore().save(cards)

        assertEquals(cards, newStore().load())
    }

    @Test
    fun aPartialUncommittedWriteKeepsThePreviousCollectionReadable() = runBlocking {
        val cards = listOf(exampleCard())
        newStore().save(cards)
        File(baseFile.path + ".new").writeText("{ incomplete write")

        assertEquals(cards, newStore().load())
    }

    @Test
    fun corruptOrFutureStorageIsNotTreatedAsFirstLaunch() {
        baseFile.writeText("{ broken")
        assertThrows(SerializationException::class.java) { runBlocking { newStore().load() } }
        assertEquals("{ broken", baseFile.readText())

        baseFile.writeText("""{"version":99,"cards":[]}""")
        assertThrows(IOException::class.java) { runBlocking { newStore().load() } }
        assertTrue(baseFile.readText().contains("99"))
    }

    @Test
    fun invalidReplacementDoesNotOverwriteTheSavedCollection() = runBlocking {
        val cards = listOf(exampleCard())
        val store = newStore()
        store.save(cards)

        assertThrows(IOException::class.java) {
            runBlocking { store.save(cards + cards) }
        }

        assertEquals(cards, store.load())
    }

    @Test
    fun aFailedAtomicRenameIsReportedInsteadOfClaimingASuccessfulSave() {
        assertTrue(baseFile.mkdir())
        File(baseFile, "blocking-entry").writeText("keep")

        assertThrows(IOException::class.java) {
            runBlocking { newStore().save(listOf(exampleCard())) }
        }

        assertTrue(baseFile.isDirectory)
        assertEquals("keep", File(baseFile, "blocking-entry").readText())
    }

    @Test
    fun versionOneMigratesWithoutChangingRulesOrSnapshots() = runBlocking {
        val card = exampleCard()
        baseFile.writeText("""{"version":1,"cards":${Json.encodeToString(listOf(card))}}""")
        val loaded = newStore().loadSpace()!!
        assertEquals(SpaceRecords(listOf(card)), loaded)
        val second = card.copy(id = "second")
        val space = SpaceRecords(listOf(card, second), listOf(CompareGroup("group", "对比", listOf(card.id, second.id))))
        newStore().saveSpace(space)
        File(baseFile.path + ".new").writeText("{ incomplete group replacement")
        assertEquals(space, newStore().loadSpace())
        assertEquals("3", json.parseToJsonElement(baseFile.readText()).jsonObject.getValue("version").jsonPrimitive.content)
    }

    @Test
    fun invalidGroupReferencesDoNotOverwriteCardsOrRelations() = runBlocking {
        val card = exampleCard()
        val second = card.copy(id = "second")
        val group = CompareGroup("group", "对比", listOf(card.id, second.id))
        val space = SpaceRecords(listOf(card, second), listOf(group))
        newStore().saveSpace(space)
        val invalid = listOf(
            listOf(group.copy(memberCardIds = listOf(card.id, "missing"))),
            listOf(group.copy(memberCardIds = listOf(card.id, card.id))),
            listOf(group, group.copy(id = "another")),
            listOf(group.copy(name = " ")),
        )
        invalid.forEach { groups ->
            assertThrows(IOException::class.java) { runBlocking { newStore().saveSpace(space.copy(groups = groups)) } }
            assertEquals(space, newStore().loadSpace())
        }
    }

    @Test
    fun versionTwoKeepsSavedGroupsDuringUpgrade() = runBlocking {
        val cards = listOf(exampleCard(), exampleCard().copy(id = "second"))
        val groups = listOf(CompareGroup("group", "旧组", cards.map { it.id }))
        baseFile.writeText("""{"version":2,"cards":${Json.encodeToString(cards)},"groups":${Json.encodeToString(groups)}}""")
        val store = newStore()
        val loaded = store.loadSpace()!!
        assertEquals(SpaceRecords(cards, groups), loaded)
        val pinned = loaded.copy(cards = listOf(cards[0].copy(pinOrder = 1), cards[1]))
        store.saveSpace(pinned)
        assertEquals(pinned, newStore().loadSpace())
    }

    @Test
    fun pageCoverAndPinnedGroupReferenceSurviveRecreation() = runBlocking {
        val task = exampleCard()
        val page = CardRecord("page", "我的书架", page = PageTarget("https://example.com/books", 123L),
            pinOrder = 1, coverUri = "content://pictures/cover")
        val space = SpaceRecords(listOf(task, page), listOf(CompareGroup("group", "阅读", listOf(task.id, page.id))))
        newStore().saveSpace(space)
        assertEquals(space, newStore().loadSpace())
    }

    private fun newStore() = AtomicFileCardStore(context, json)

    private fun exampleCard(): CardRecord {
        val template = TemplateCatalog.templates.first()
        return CardRecord(
            id = "saved-card-1",
            title = template.title,
            kind = template.kind,
            rule = template.rule,
            snapshot = CardSnapshot(listOf(FieldValue("pickupCode", "取件码", "A3-6281")), fetchedAt = 1234L),
        )
    }
}
