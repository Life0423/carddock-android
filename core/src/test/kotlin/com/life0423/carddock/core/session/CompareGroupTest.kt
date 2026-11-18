package com.life0423.carddock.core.session

import com.life0423.carddock.core.engine.RuleEngine
import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.model.TemplateDefinition
import com.life0423.carddock.core.testing.FixedCompiler
import com.life0423.carddock.core.testing.MemoryCardStore
import com.life0423.carddock.core.testing.ObservedDevice
import com.life0423.carddock.core.testing.UiFixture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** 验证真实控制器的组引用、顺序、原子失败与再次启动行为，不另写业务算法。 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompareGroupTest {
    private val originals = (1..5).map { UiFixture.card.copy(id = "card-$it", title = "卡片 $it") }
    private val template = TemplateDefinition("test", "卡片", "测试", originals.first().kind, "读取", originals.first().rule)

    @Test fun `create rename remove dissolve and restart preserve every skill`() = runTest {
        val store = MemoryCardStore(originals)
        val controller = controller(store)
        controller.initialize()
        runCurrent()
        controller.actions.createGroup(" 对比 ", listOf("card-4", "card-2", "card-5"))
        runCurrent()
        val group = controller.state.value.groups.single()
        assertEquals("对比", group.name)
        assertEquals(listOf("card-1", "card-4", "card-2", "card-5", "card-3"), store.disk!!.map { it.id })
        assertEquals(originals.associateBy { it.id }, store.disk!!.associateBy { it.id })

        controller.actions.renameGroup(group.id, "修改后的名称")
        runCurrent()
        assertEquals("修改后的名称", store.groups.single().name)
        controller.actions.removeGroupMember(group.id, "card-4")
        runCurrent()
        assertEquals(listOf("card-2", "card-5"), store.groups.single().memberCardIds)
        assertEquals(listOf("card-1", "card-2", "card-5", "card-4", "card-3"), store.disk!!.map { it.id })

        val restarted = controller(store)
        restarted.initialize()
        runCurrent()
        assertEquals(controller.state.value.groups, restarted.state.value.groups)
        assertEquals(controller.state.value.cards, restarted.state.value.cards)
        restarted.actions.dissolveGroup(group.id)
        runCurrent()
        assertTrue(store.groups.isEmpty())
        assertEquals(originals.associateBy { it.id }, store.disk!!.associateBy { it.id })
    }

    @Test fun `invalid groups never partially change the space`() = runTest {
        val store = MemoryCardStore(originals)
        val controller = controller(store)
        controller.initialize()
        runCurrent()
        val invalidMembers = listOf(emptyList(), listOf("card-1"), listOf("card-1", "card-1"), listOf("card-1", "missing"))
        invalidMembers.forEach { members ->
            controller.actions.createGroup("组", members)
            runCurrent()
            assertTrue(store.groups.isEmpty())
            assertEquals(originals, store.disk)
        }
        controller.actions.createGroup("  ", listOf("card-1", "card-2"))
        runCurrent()
        assertTrue(store.groups.isEmpty())
        controller.actions.createGroup("组", listOf("card-1", "card-2"))
        runCurrent()
        val saved = store.groups
        controller.actions.createGroup("重复归属", listOf("card-2", "card-3"))
        controller.actions.renameGroup(saved.single().id, " ")
        runCurrent()
        assertEquals(saved, store.groups)
    }

    @Test fun `two member removal dissolves and card deletion cleans references`() = runTest {
        val store = MemoryCardStore(originals)
        val controller = controller(store)
        controller.initialize()
        runCurrent()
        controller.actions.createGroup("组", listOf("card-1", "card-2"))
        runCurrent()
        controller.actions.removeGroupMember(store.groups.single().id, "card-1")
        runCurrent()
        assertTrue(store.groups.isEmpty())
        assertEquals(originals.associateBy { it.id }, store.disk!!.associateBy { it.id })
        controller.actions.createGroup("组", listOf("card-1", "card-2", "card-3"))
        runCurrent()
        controller.actions.deleteCard("card-2")
        runCurrent()
        assertEquals(listOf("card-1", "card-3"), store.groups.single().memberCardIds)
        controller.actions.deleteCard("card-3")
        runCurrent()
        assertTrue(store.groups.isEmpty())
        assertEquals(setOf("card-1", "card-4", "card-5"), store.disk!!.map { it.id }.toSet())
    }

    @Test fun `failed commit preserves the whole space and postcommit readback reconciles it`() = runTest {
        val store = MemoryCardStore(originals)
        val controller = controller(store)
        controller.initialize()
        runCurrent()
        store.failWrite = true
        controller.actions.createGroup("失败", listOf("card-2", "card-4"))
        runCurrent()
        assertEquals(originals, controller.state.value.cards)
        assertTrue(controller.state.value.groups.isEmpty())
        store.failWrite = false
        store.failAfterCommit = true
        controller.actions.createGroup("已提交", listOf("card-2", "card-4"))
        runCurrent()
        assertEquals(store.groups, controller.state.value.groups)
        assertEquals(store.disk, controller.state.value.cards)
        assertEquals("已提交", store.groups.single().name)
    }

    @Test fun `card refresh keeps group and resetting explicitly clears it`() = runTest {
        val store = MemoryCardStore(originals)
        val controller = controller(store)
        controller.initialize()
        runCurrent()
        controller.actions.createGroup("保留", listOf("card-1", "card-2"))
        runCurrent()
        val groups = store.groups
        controller.actions.refreshCard("card-1")
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(groups, store.groups)
        controller.actions.resetDemo()
        runCurrent()
        assertTrue(store.groups.isEmpty())
    }

    private fun TestScope.controller(store: MemoryCardStore): PrototypeController {
        val rule = template.rule
        return PrototypeController(store, FixedCompiler(template),
            RuleEngine(ObservedDevice(mapOf(rule.sourceId to rule.targetKey))), listOf(template), backgroundScope)
    }
}
