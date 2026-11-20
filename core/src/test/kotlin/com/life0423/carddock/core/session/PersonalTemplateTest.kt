package com.life0423.carddock.core.session

import com.life0423.carddock.core.engine.RuleEngine
import com.life0423.carddock.core.model.LocalTarget
import com.life0423.carddock.core.model.PersonalTemplate
import com.life0423.carddock.core.model.RunPhase
import com.life0423.carddock.core.model.TemplateDefinition
import com.life0423.carddock.core.model.TemplateKind
import com.life0423.carddock.core.port.CompileResult
import com.life0423.carddock.core.port.DeviceGateway
import com.life0423.carddock.core.port.DeviceResponse
import com.life0423.carddock.core.port.DiscoveredTemplate
import com.life0423.carddock.core.port.DiscoveryResult
import com.life0423.carddock.core.port.DeviceSnapshot
import com.life0423.carddock.core.port.ReadOptions
import com.life0423.carddock.core.port.RuleCompiler
import com.life0423.carddock.core.port.TemplateStore
import com.life0423.carddock.core.port.orderIdentity
import com.life0423.carddock.core.rule.RuleSpec
import com.life0423.carddock.core.rule.RuleStep
import com.life0423.carddock.core.testing.MemoryCardStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PersonalTemplateTest {
    /** 首轮学习出的值不进入规则；独立重跑不一致时不能留下可保存的候选。 */
    @Test fun discoveryMustMatchIndependentReplay() = runTest {
        val rule = RuleSpec(id = "agenda", sourceId = "calendar", targetKey = "agenda", steps = listOf(
            RuleStep.AssertTarget("agenda"), RuleStep.ReadField("name", "name", "名称")))
        val template = TemplateDefinition("agenda", "提醒", "今日通知", TemplateKind.REMINDER, "看提醒", rule)
        var value = "已变更"
        val device = object : DeviceGateway {
            override suspend fun inspect(sourceId: String, targetKey: String) =
                DeviceResponse.Ready(DeviceSnapshot(sourceId, targetKey, mapOf("name" to value)))
        }
        val compiler = object : RuleCompiler {
            override suspend fun compile(prompt: String) = CompileResult.Unsupported("无固定模板")
        }
        val controller = PrototypeController(MemoryCardStore(emptyList()), compiler, RuleEngine(device),
            emptyList(), backgroundScope, discover = { _, _ ->
                DiscoveryResult.Found(DiscoveredTemplate(template, mapOf("name" to "样本")))
            })
        controller.initialize()
        with(controller.actions) {
            openTemplateEditor(null)
            changePrompt("看提醒")
            prepareTemplate()
            runCurrent()
            assertEquals(RunPhase.NEEDS_USER, controller.state.value.draft?.phase)
            assertNull(controller.state.value.draft?.template)
            assertNull(controller.state.value.draft?.preview)
            value = "样本"
            prepareTemplate()
            runCurrent()
            assertEquals(RunPhase.SUCCEEDED, controller.state.value.draft?.phase)
            assertEquals("样本", controller.state.value.draft?.preview?.fields?.single()?.value)
        }
    }

    /** 歧义仅允许从本轮候选示范一次。选择本身不能替代规则引擎的独立实读。 */
    @Test fun demonstrationReplaysFieldBeforePreview() = runTest {
        val rule = RuleSpec(id = "test", sourceId = "local", targetKey = "counts", steps = listOf(
            RuleStep.AssertTarget("counts"), RuleStep.ReadField("quantity", "quantity", "数量")))
        val template = TemplateDefinition("test","分类数量","查看数量",TemplateKind.QUERY,"查询",rule)
        val choices = listOf("3 项","7 项").map { DiscoveredTemplate(template, mapOf("quantity" to it)) }
        var calls = 0
        val gateway = object : DeviceGateway {
            override suspend fun inspect(sourceId: String, targetKey: String): DeviceResponse {
                calls++
                return DeviceResponse.Ready(DeviceSnapshot(sourceId, targetKey, mapOf("quantity" to "7 项")))
            }
        }
        val compiler = object : RuleCompiler {
            override suspend fun compile(prompt: String) = CompileResult.Unsupported("需要试查")
        }
        val controller = PrototypeController(MemoryCardStore(emptyList()),compiler,RuleEngine(gateway),
            emptyList(),backgroundScope,discover = { _, _ -> DiscoveryResult.Demonstrate(choices) })
        controller.initialize()
        with(controller.actions) {
            openTemplateEditor(null)
            changePrompt("查询")
            prepareTemplate()
            runCurrent()
            assertEquals(2,controller.state.value.draft?.demonstrations?.size)
            assertNull(controller.state.value.draft?.preview)
            changePrompt("新查询")
            demonstrateField(1)
            runCurrent()
            assertEquals(0,calls)
            prepareTemplate()
            runCurrent()
            demonstrateField(1)
            runCurrent()
            assertEquals(1,calls)
            assertEquals(RunPhase.SUCCEEDED,controller.state.value.draft?.phase)
            assertEquals("7 项",controller.state.value.draft?.preview?.fields?.single()?.value)
        }
    }

    @Test fun pickupActionMustReplayTwiceBeforePublishingAndOnceForNewCard() = runTest {
        val rule = RuleSpec(id="pickup",sourceId="parcel",targetKey="status",steps=listOf(
            RuleStep.AssertTarget("status"),RuleStep.ReadField("status","status","待取状态")))
        val entry = com.life0423.carddock.core.model.PickupEntry.OUTBOUND
        val definition = TemplateDefinition("pickup","取件","待取并亮码",TemplateKind.EXPRESS,"查快递并出示身份码",rule,pickupEntry=entry)
        val compiler = object : RuleCompiler {
            override suspend fun compile(prompt: String) = CompileResult.Unsupported("需要试查")
        }
        val device = object : DeviceGateway {
            override suspend fun inspect(sourceId: String,targetKey: String) =
                DeviceResponse.Ready(DeviceSnapshot(sourceId,targetKey,mapOf("status" to "暂无待取包裹")))
        }
        var runs = 0
        val library = object : TemplateStore {
            var disk = emptyList<PersonalTemplate>()
            override suspend fun load() = disk
            override suspend fun save(templates: List<PersonalTemplate>) { disk = templates }
        }
        val controller = PrototypeController(MemoryCardStore(emptyList()),compiler,RuleEngine(device),emptyList(),backgroundScope,
            templateStore=library,verifyPickup={ runs++; if (runs == 1) "需要授权" else null },
            discover={ _,_ -> DiscoveryResult.Found(DiscoveredTemplate(definition,mapOf("status" to "暂无待取包裹"))) })
        controller.initialize()
        with(controller.actions) {
            openTemplateEditor(null)
            changePrompt(definition.examplePrompt)
            prepareTemplate(); runCurrent()
            assertEquals(RunPhase.NEEDS_USER,controller.state.value.draft?.phase)
            saveDraft(); runCurrent()
            assertTrue(library.disk.isEmpty())
            trialDraft(); runCurrent()
            assertEquals(3,runs)
            saveDraft(); runCurrent()
            val published = library.disk.single().published!!
            assertEquals(entry,published.pickupEntry)
            openCreator(published); trialDraft(); runCurrent(); saveDraft(); runCurrent()
            val card = controller.state.value.cards.single()
            assertEquals(entry,card.pickupEntry)
            assertEquals(4,runs)
            refreshCard(card.id); runCurrent()
            assertEquals(4,runs)
        }
    }

    @Test fun verifiedDefinitionAndCardHaveSeparateStorageAndVersion() = runTest {
        val rule = RuleSpec(id = "order", sourceId = "orders", targetKey = "completed", steps = listOf(
            RuleStep.AssertTarget("completed"), RuleStep.ReadField("status", "status", "状态"),
        ))
        val builtIn = TemplateDefinition("orders", "订单", "核验已完成订单", TemplateKind.DELIVERY, "核验订单",
            rule, requiresOrderNumber = true)
        val number = "12345678901234567890123"
        val route = "1234567890123456789"
        val library = object : TemplateStore {
            var disk = emptyList<PersonalTemplate>()
            var failWrite = false
            override suspend fun load() = disk
            override suspend fun save(templates: List<PersonalTemplate>) {
                if (failWrite) throw java.io.IOException()
                disk = templates
            }
        }
        val cards = MemoryCardStore(emptyList())
        val gateway = object : DeviceGateway {
            override suspend fun inspect(sourceId: String, targetKey: String) = error("Missing target")
            override suspend fun inspect(sourceId: String, targetKey: String, options: ReadOptions): DeviceResponse {
                assertEquals(LocalTarget(number, route), options.localTarget)
                return DeviceResponse.Ready(DeviceSnapshot(sourceId, orderIdentity(number), mapOf("status" to "已完成")))
            }
        }
        val compiler = object : RuleCompiler {
            override suspend fun compile(prompt: String) = CompileResult.Unsupported("已选模板应直接执行")
        }
        fun controller() = PrototypeController(cards, compiler, RuleEngine(gateway), listOf(builtIn), backgroundScope,
            templateStore = library)
        var controller = controller()
        controller.initialize()
        runCurrent()

        var actions = controller.actions
        actions.openTemplateEditor(null)
        actions.changePrompt("仅保存本机需求，尚无匹配能力")
        actions.keepTemplateDraft()
        runCurrent()
        val draftId = library.disk.single().id
        assertNull(library.disk.single().published)
        actions.openTemplateEditor(draftId)
        runCurrent()
        assertEquals("仅保存本机需求，尚无匹配能力", controller.state.value.draft?.prompt)
        actions.chooseDraftTemplate(builtIn)
        actions.changePrompt("换一个查询来源")
        runCurrent()
        assertEquals("", controller.state.value.draft?.title)
        assertEquals("", controller.state.value.draft?.description)
        actions.chooseDraftTemplate(builtIn)
        actions.changeTitle("我命名的订单")
        actions.changePrompt("调整展示内容")
        runCurrent()
        assertEquals("我命名的订单", controller.state.value.draft?.title)
        actions.chooseDraftTemplate(builtIn)
        actions.changeOrderNumber(number)
        actions.changeRouteOid(route)
        actions.saveDraft()
        assertNull(library.disk.single().published)
        actions.keepTemplateDraft()
        runCurrent()
        val id = library.disk.single().id
        assertNull(library.disk.single().published)

        controller = controller()
        controller.initialize()
        runCurrent()
        actions = controller.actions
        actions.openTemplateEditor(id)
        runCurrent()
        assertEquals("", controller.state.value.draft?.orderNumber)
        assertNull(controller.state.value.draft?.preview)
        actions.changeOrderNumber(number)
        actions.changeRouteOid(route)
        actions.trialDraft()
        runCurrent()
        assertEquals(RunPhase.SUCCEEDED, controller.state.value.draft?.phase)
        library.failWrite = true
        actions.saveDraft()
        runCurrent()
        assertNotNull(controller.state.value.draft?.preview)
        assertNull(library.disk.single().published)
        library.failWrite = false
        actions.changeDisplayFields(listOf(com.life0423.carddock.core.model.DisplayField("status", "进度")))
        actions.saveDraft()
        runCurrent()
        assertNull(library.disk.single().published)
        actions.trialDraft()
        runCurrent()
        actions.changeTitle("个人订单")
        actions.saveDraft()
        runCurrent()
        val published = requireNotNull(library.disk.single().published)
        val exported = Json.encodeToString(library.disk)
        assertFalse(exported.contains(number) || exported.contains(route) || exported.contains("已完成\""))
        assertTrue(cards.disk!!.isEmpty())

        actions.openCreator(published)
        actions.changeOrderNumber(number)
        actions.changeRouteOid(route)
        actions.trialDraft()
        runCurrent()
        actions.changeRouteOid("0000000000000000000")
        actions.saveDraft()
        runCurrent()
        assertTrue(cards.disk!!.isEmpty())
        actions.changeRouteOid(route)
        actions.trialDraft()
        runCurrent()
        actions.saveDraft()
        runCurrent()
        val saved = cards.disk!!.single()
        assertEquals("进度", saved.display.single().label)
        assertEquals(LocalTarget(number, route), saved.localTarget)
        assertEquals(id, saved.sourceTemplateId)
        assertEquals(1, saved.sourceTemplateRevision)
        actions.deleteTemplate(id)
        runCurrent()
        actions.refreshCard(saved.id)
        runCurrent()
        assertEquals(RunPhase.SUCCEEDED, cards.disk!!.single().run?.phase)
    }
}
