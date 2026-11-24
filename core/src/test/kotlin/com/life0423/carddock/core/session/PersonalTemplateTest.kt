package com.life0423.carddock.core.session

import com.life0423.carddock.core.model.ticketFace
import com.life0423.carddock.core.model.DisplayField
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

    /** 源菜单的候选不授予保存资格；复核后的分组和门店要一直传递到空间刷新。 */
    @Test fun fixedMealRequiresReplayAndKeepsPreviousResultWhenUnavailable() = runTest {
        val menu = com.life0423.carddock.core.rule.MealMenu("示例餐厅", mapOf("第一组" to listOf("甲"), "第二组" to listOf("乙")))
        val dish = com.life0423.carddock.core.rule.MealTarget("示例套餐")
        val key = "REPEAT_MEAL"
        val rule = RuleSpec(id = "fixed", sourceId = "menu", targetKey = key,
            steps = listOf(RuleStep.AssertTarget(key), RuleStep.ReadField("pair","pair","固定搭配")),
            officialAction = com.life0423.carddock.core.rule.OfficialAction.REPEAT_MEAL, meal = dish)
        val template = TemplateDefinition("fixed","常用搭配","核验菜单",TemplateKind.SHOPPING,"查看固定搭配", rule)
        val picked = dish.copy(store = menu.store, choices = mapOf("第一组" to "甲", "第二组" to "乙"))
        var available = true
        val device = object : DeviceGateway {
            override suspend fun inspect(sourceId: String, targetKey: String): DeviceResponse = error("缺少搭配参数")
            override suspend fun inspect(sourceId: String, targetKey: String, options: ReadOptions): DeviceResponse {
                if (options.meal == dish) return DeviceResponse.NeedsUser("请选择", mealMenu = menu)
                assertEquals(picked, options.meal)
                return if (available) DeviceResponse.Ready(DeviceSnapshot(sourceId, key, mapOf("pair" to "甲 + 乙")))
                    else DeviceResponse.NeedsUser("本次菜单未见该套餐")
            }
        }
        val cards = MemoryCardStore(emptyList())
        val compiler = object : RuleCompiler { override suspend fun compile(prompt: String) = CompileResult.Success(template) }
        val controller = PrototypeController(cards, compiler, RuleEngine(device), emptyList(), backgroundScope)
        controller.initialize()
        with(controller.actions) {
            openTemplateEditor(null); chooseDraftTemplate(template); trialDraft(); runCurrent()
            assertEquals(menu, controller.state.value.draft?.mealMenu)
            assertNull(controller.state.value.draft?.preview)
            saveDraft(); runCurrent()
            assertTrue(controller.state.value.personalTemplates.isEmpty())
            chooseDraftTemplate(template.copy(rule = rule.copy(meal = picked))); trialDraft(); runCurrent()
            saveDraft(); runCurrent()
            val saved = requireNotNull(controller.state.value.personalTemplates.single().published)
            assertEquals(picked, saved.rule.meal)
            openCreator(saved); trialDraft(); runCurrent(); saveDraft(); runCurrent()
            val card = cards.disk!!.single()
            available = false
            refreshCard(card.id); runCurrent()
            assertEquals(card.snapshot, cards.disk!!.single().snapshot)
            assertEquals(RunPhase.NEEDS_USER, cards.disk!!.single().run?.phase)
        }
    }

    @Test fun monthlyReplayUsesSameCutoffAndRejectsDifferentOrdersWithTheSameTotal() = runTest {
        val rule = RuleSpec(id="ledger",sourceId="orders",targetKey="monthly-original-paid",
            steps=listOf(RuleStep.AssertTarget("monthly-original-paid"),RuleStep.ReadField("paid","paid","实付合计")),
            orderPath=listOf("我的","全部订单"))
        val template = TemplateDefinition("ledger","月账本","近月实付",TemplateKind.QUERY,"统计已完成订单",rule)
        val fields = mapOf("paid" to "18.90")
        val cutoff = 1_800_000_000_000L
        var replayDigest = "different-orders"
        var expectedCutoff: Long? = cutoff
        var confirmedFields = fields
        var complete = true
        var clock = 0L
        val device = object : DeviceGateway {
            override suspend fun inspect(sourceId: String,targetKey: String) = error("需要截止时刻和路线")
            override suspend fun inspect(sourceId: String,targetKey: String,options: ReadOptions): DeviceResponse {
                assertEquals(expectedCutoff,options.requestedAt)
                assertEquals(rule.orderPath,options.orderPath)
                if (!complete) return DeviceResponse.NeedsUser("来源没有给出完整订单数量。")
                return DeviceResponse.Ready(DeviceSnapshot(sourceId,targetKey,confirmedFields,evidenceDigest=replayDigest))
            }
        }
        val compiler = object : RuleCompiler {
            override suspend fun compile(prompt: String) = CompileResult.Unsupported("需要试查")
        }
        val cards = MemoryCardStore(emptyList())
        val library = object : TemplateStore {
            var entries = emptyList<PersonalTemplate>()
            override suspend fun load() = entries
            override suspend fun save(templates: List<PersonalTemplate>) { entries = templates }
        }
        val controller = PrototypeController(cards,compiler,RuleEngine(device,now={ ++clock }),emptyList(),backgroundScope,
            templateStore=library,discover={ _,_ -> DiscoveryResult.Found(DiscoveredTemplate(template,fields,cutoff,"verified-orders")) })
        controller.initialize()
        with(controller.actions) {
            openTemplateEditor(null)
            changePrompt(template.examplePrompt)
            prepareTemplate(); runCurrent()
            assertEquals(RunPhase.NEEDS_USER,controller.state.value.draft?.phase)
            assertNull(controller.state.value.draft?.preview)
            replayDigest = "verified-orders"
            prepareTemplate(); runCurrent()
            assertEquals(RunPhase.SUCCEEDED,controller.state.value.draft?.phase)
            assertEquals(replayDigest,controller.state.value.draft?.preview?.evidenceDigest)
            saveDraft(); runCurrent()
            val saved = requireNotNull(library.entries.single().published)
            assertEquals(rule.orderPath,saved.rule.orderPath)
            expectedCutoff = null // 此后每次建卡或刷新都以当前时刻重新查询。
            openCreator(saved); trialDraft(); runCurrent(); saveDraft(); runCurrent()
            val card = cards.disk!!.single()
            confirmedFields = mapOf("paid" to "25.10")
            replayDigest = "new-orders"
            refreshCard(card.id); runCurrent()
            val updated = requireNotNull(cards.disk!!.single().snapshot)
            assertEquals("25.10",updated.fields.single().value)
            assertTrue(updated.fetchedAt > requireNotNull(card.snapshot).fetchedAt)
            complete = false
            refreshCard(card.id); runCurrent()
            assertEquals(RunPhase.NEEDS_USER,cards.disk!!.single().run?.phase)
            assertEquals(updated,cards.disk!!.single().snapshot)
        }
    }

    /** 已核验字段换序无需重查，保存的模板、卡片及后续刷新沿用所选主字段。 */
    @Test fun reorderedPrimarySurvivesSaveAndRefresh() = runTest {
        val rule = RuleSpec(id = "agenda", sourceId = "calendar", targetKey = "agenda", steps = listOf(
            RuleStep.AssertTarget("agenda"), RuleStep.ReadField("name", "name", "名称"),
            RuleStep.ReadField("time", "time", "时间")))
        val template = TemplateDefinition("agenda", "提醒", "下一条提醒", TemplateKind.REMINDER, "看提醒", rule)
        var reads = 0
        val gateway = object : DeviceGateway {
            override suspend fun inspect(sourceId: String, targetKey: String): DeviceResponse {
                reads++
                return DeviceResponse.Ready(DeviceSnapshot(sourceId, targetKey, mapOf("name" to "测试", "time" to "16:0$reads")))
            }
        }
        val compiler = object : RuleCompiler {
            override suspend fun compile(prompt: String) = CompileResult.Success(template)
        }
        val controller = PrototypeController(MemoryCardStore(emptyList()), compiler, RuleEngine(gateway),
            listOf(template), backgroundScope)
        controller.initialize()
        with(controller.actions) {
            openTemplateEditor(null); changePrompt(template.examplePrompt); prepareTemplate(); runCurrent()
            val preview = requireNotNull(controller.state.value.draft?.preview)
            val layout = preview.fields.reversed().map { DisplayField(it.key, it.label) }
            changeDisplayFields(layout)
            assertEquals(preview, controller.state.value.draft?.preview)
            assertEquals(1, reads)
            saveDraft(); runCurrent()
            val saved = requireNotNull(controller.state.value.personalTemplates.single().published)
            openCreator(saved); trialDraft(); runCurrent(); saveDraft(); runCurrent()
            refreshCard(controller.state.value.cards.single().id); runCurrent()
            val card = controller.state.value.cards.single()
            assertEquals(layout, card.display)
            assertEquals("16:03", requireNotNull(card.snapshot).ticketFace(card.display, card.rule).fields.first().value)
        }
    }

    /** 卡片实例可单独换主字段，已发布的模板与试运行时间保持独立。 */
    @Test fun cardPrimaryOverrideKeepsEvidenceAndPublishedLayout() = runTest {
        val rule = RuleSpec(id = "order", sourceId = "store", targetKey = "detail", steps = listOf(
            RuleStep.AssertTarget("detail"), RuleStep.ReadField("status", "status", "状态"),
            RuleStep.ReadField("paid", "paid", "实付（元）")))
        val template = TemplateDefinition("store", "订单", "只读", TemplateKind.QUERY, "查看订单", rule)
        val gateway = object : DeviceGateway {
            override suspend fun inspect(sourceId: String, targetKey: String) =
                DeviceResponse.Ready(DeviceSnapshot(sourceId, targetKey, mapOf("status" to "已完成", "paid" to "20.50")))
        }
        val compiler = object : RuleCompiler {
            override suspend fun compile(prompt: String) = CompileResult.Success(template)
        }
        val controller = PrototypeController(MemoryCardStore(emptyList()), compiler, RuleEngine(gateway),
            listOf(template), backgroundScope)
        controller.initialize()
        with(controller.actions) {
            openTemplateEditor(null); changePrompt(template.examplePrompt); prepareTemplate(); runCurrent()
            saveDraft(); runCurrent()
            val published = requireNotNull(controller.state.value.personalTemplates.single().published)
            openCreator(published); trialDraft(); runCurrent()
            val preview = requireNotNull(controller.state.value.draft?.preview)
            val layout = listOf(DisplayField("paid", "实付（元）"), DisplayField("status", "状态"))
            changeDisplayFields(layout)
            assertEquals(preview, controller.state.value.draft?.preview)
            saveDraft(); runCurrent()
            val card = controller.state.value.cards.single()
            assertEquals(layout, card.display)
            assertTrue(requireNotNull(controller.state.value.personalTemplates.single().published).display.isEmpty())
            refreshCard(card.id); runCurrent()
            val refreshed = controller.state.value.cards.single()
            val face = requireNotNull(refreshed.snapshot).ticketFace(refreshed.display, refreshed.rule)
            assertEquals(com.life0423.carddock.core.model.TicketRole.AMOUNT, face.role)
            assertEquals("20.50", face.fields.first().value)
            setCardPrimary(card.id, "status"); runCurrent()
            val changed = controller.state.value.cards.single()
            assertEquals(refreshed.snapshot, changed.snapshot)
            assertEquals("status", changed.display.first().key)
            assertTrue(requireNotNull(controller.state.value.personalTemplates.single().published).display.isEmpty())
        }
    }

    /** 读到空态也要单独验证交接。保存的列表卡不绑定包裹，点卡只发起状态查询。 */
    @Test fun listHandoffIsRequiredForEachNewCard() = runTest {
        val rule = RuleSpec(id = "list", sourceId = "parcel", targetKey = "status", steps = listOf(
            RuleStep.AssertTarget("status"), RuleStep.ReadField("status", "status", "待取状态")))
        val template = TemplateDefinition("list", "待取列表", "进入菜鸟后选择包裹", TemplateKind.EXPRESS,
            "打开菜鸟待取列表", rule, opensPickupList = true)
        var available = true
        var handoffs = 0
        val controller = PrototypeController(MemoryCardStore(emptyList()), object : RuleCompiler {
            override suspend fun compile(prompt: String) = CompileResult.Success(template)
        }, RuleEngine(object : DeviceGateway {
            override suspend fun inspect(sourceId: String, targetKey: String) =
                DeviceResponse.Ready(DeviceSnapshot(sourceId, targetKey, mapOf("status" to "暂无待取包裹")))
        }), emptyList(), backgroundScope, verifyPickupList = {
            handoffs++
            if (available) null else DeviceResponse.NeedsUser("未能交接列表", com.life0423.carddock.core.model.PhoneCapability.HELPER)
        })
        controller.initialize()
        with(controller.actions) {
            openTemplateEditor(null)
            changePrompt(template.examplePrompt)
            prepareTemplate(); runCurrent(); saveDraft(); runCurrent()
            val saved = controller.state.value.personalTemplates.single().published!!
            assertTrue(saved.opensPickupList)
            openCreator(saved)
            available = false
            trialDraft(); runCurrent(); saveDraft(); runCurrent()
            assertEquals(RunPhase.NEEDS_USER, controller.state.value.draft?.phase)
            assertEquals(com.life0423.carddock.core.model.PhoneCapability.HELPER, controller.state.value.draft?.missingCapability)
            assertTrue(controller.state.value.cards.isEmpty())
            available = true
            trialDraft(); runCurrent(); saveDraft(); runCurrent()
            val card = controller.state.value.cards.single()
            assertTrue(card.opensPickupList)
            assertNull(card.localTarget)
            assertNull(card.pickupEntry)
            refreshCard(card.id); runCurrent()
            assertEquals(3, handoffs)
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
            templateStore=library,verifyPickup={ runs++; if (runs == 1) DeviceResponse.NeedsUser("需要授权") else null },
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
