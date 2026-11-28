package com.life0423.carddock.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.life0423.carddock.PrototypeTools
import com.life0423.carddock.jdTemplates
import com.life0423.carddock.core.model.TemplateKind
import com.life0423.carddock.core.model.FieldValue
import com.life0423.carddock.core.model.PersonalTemplate
import com.life0423.carddock.core.model.TemplateEdit
import com.life0423.carddock.core.model.RunInfo
import com.life0423.carddock.core.model.RunPhase
import com.life0423.carddock.core.session.CreationDraft
import com.life0423.carddock.core.testing.UiFixture
import com.life0423.carddock.demo.TemplateCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** User-visible contracts and real 360 dp native renders, without app services. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h800dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CardDockUiTest {
    // 非调试包不携带 Compose 测试清单，仅在 Robolectric 的包管理器中声明测试宿主。
    @get:Rule(order = 0)
    val host: org.junit.rules.ExternalResource = object : org.junit.rules.ExternalResource() {
        override fun before() {
            val app = org.robolectric.RuntimeEnvironment.getApplication()
            org.robolectric.Shadows.shadowOf(app.packageManager).addOrUpdateActivity(android.content.pm.ActivityInfo().apply {
                name = androidx.activity.ComponentActivity::class.java.name
                packageName = app.packageName
                applicationInfo = app.applicationInfo
            })
        }
    }
    @get:Rule(order = 1)
    val compose = createComposeRule()

    /** 账号从「我的」进入，验证注册表单和关闭后的返回位置。 */
    @Test
    fun profileOwnsLoginAndRegistration() {
        compose.setContent {
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            val context = androidx.compose.ui.platform.LocalContext.current
            val store = androidx.compose.runtime.remember {
                com.life0423.carddock.data.SquareStore(context, "http://127.0.0.1:8765", scope)
            }
            CardDockApp(UiFixture.state(TemplateCatalog.templates), UiFixture.actions(mutableListOf()), squareStore = store)
        }
        compose.onNodeWithTag("tab_profile").performClick()
        compose.captureScreen("07-account-profile")
        compose.onNodeWithText("登录或注册").performClick()
        compose.onNodeWithText("创建账号").performClick()
        compose.onNodeWithText("用户名").assertIsDisplayed()
        compose.onNodeWithText("已有账号，去登录").assertIsDisplayed()
        compose.captureScreen("08-account-register")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("tab_profile").assertIsSelected()
    }

    @Test
    fun nativeTemplateAndPermissionEntryAreReachable() {
        var settingsOpened = false
        val templates = TemplateCatalog.templates + com.life0423.carddock.cainiaoEmptyTemplate
        compose.setContent { CardDockApp(UiFixture.state(templates), UiFixture.actions(mutableListOf()), { settingsOpened = true }) }
        compose.onNodeWithTag("tab_templates").performClick()
        compose.onNodeWithTag("new_template").assertIsDisplayed()
        compose.onNodeWithText("内置模板").assertDoesNotExist()
        compose.captureScreen("05-native-template")
        compose.onNodeWithTag("tab_profile").performClick()
        compose.onNodeWithTag("profile_list").performScrollToNode(hasTestTag("accessibility_settings"))
        compose.captureScreen("06-native-settings")
        compose.onNodeWithTag("accessibility_settings").performClick()
        assertTrue(settingsOpened)
    }

    @Test
    fun navigationTemplatesAndResetDispatchExplicitActions() {
        val events = mutableListOf<String>()
        compose.setContent { CardDockApp(UiFixture.state(TemplateCatalog.templates), UiFixture.actions(events)) }
        compose.onNodeWithTag("tab_space").assertIsSelected()
        compose.captureScreen("01-space")
        compose.onNodeWithTag("tab_templates").performClick().assertIsSelected()
        compose.onNodeWithTag("library_section_1").performClick()
        compose.onNodeWithText("暂无草稿").assertIsDisplayed()
        compose.onNodeWithText("收藏").assertDoesNotExist()
        compose.captureScreen("02-templates")
        compose.onNodeWithTag("tab_square").performClick().assertIsSelected()

        compose.onNodeWithTag("tab_profile").performClick().assertIsSelected()
        compose.captureScreen("04-profile")
        compose.onNodeWithTag("toggle_demo").performClick()
        compose.onNodeWithTag("profile_list")
            .performScrollToNode(hasTestTag("scenario_READ_FAILURE"))
        compose.onNodeWithTag("scenario_READ_FAILURE").performClick()
        compose.onNodeWithTag("profile_list").performScrollToNode(hasText("数据管理"))
        compose.onNodeWithText("数据管理").performClick()
        compose.onNodeWithTag("reset_demo").performClick()
        compose.onNodeWithTag("confirm_reset").assertIsDisplayed().performClick()

        assertEquals(listOf("scenario:READ_FAILURE", "reset"), events)
    }

    @Test
    @Config(application = android.app.Application::class)
    fun pickerStartsMikaTrialEditorForANewTemplate() {
        val ready = com.life0423.carddock.cainiaoEmptyTemplate
        compose.setContent { CardDockApp(UiFixture.state(listOf(ready)), UiFixture.actions(mutableListOf())) }
        compose.onNodeWithTag("open_creator").performClick()
        compose.onNodeWithTag("picker_new_template").performClick()
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val intent = org.robolectric.Shadows.shadowOf(app).nextStartedActivity
        assertEquals(MikaTasksActivity::class.java.name, intent.component?.className)
        assertTrue(intent.getBooleanExtra("routes", false))
    }

    /** 同一存储里的实例、模板和待确认路线分别来自不同入口；菜单中删除必须再确认。 */
    @Test
    @Config(application = android.app.Application::class)
    fun routeInstancesAndDraftsHaveSeparateHomes() {
        val rows = com.wangzan101.mika.task.DockRoutes(org.robolectric.RuntimeEnvironment.getApplication())
        val card = com.wangzan101.mika.task.DockRoute("test-card", "我的查询卡", "old",
            phase = com.wangzan101.mika.task.DockRoute.Phase.Ready, saved = true, templateId = "test-template")
        val template = card.copy(id = "test-template", goal = "可用的查询模板", isTemplate = true, templateId = null)
        val draft = card.copy(id = "test-draft", goal = "未完成的试查", saved = false,
            phase = com.wangzan101.mika.task.DockRoute.Phase.Failed)
        listOf(card, template, draft).forEach {
            rows.prefs.edit().putString(it.id, kotlinx.serialization.json.Json.encodeToString(
                com.wangzan101.mika.task.DockRoute.serializer(), it)).commit()
        }
        try {
            compose.setContent { CardDockApp(UiFixture.state(emptyList()), UiFixture.actions(mutableListOf())) }
            compose.onNodeWithText(card.goal).assertIsDisplayed()
            compose.onNodeWithText(draft.goal).assertDoesNotExist()
            compose.onNodeWithContentDescription("${card.goal}的更多操作").performClick()
            compose.onNodeWithText("删除卡片").performClick()
            compose.onNodeWithText("取消").performClick()
            compose.onNodeWithTag("tab_templates").performClick()
            compose.onNodeWithText(template.goal).assertIsDisplayed()
            compose.onNodeWithText(draft.goal).assertDoesNotExist()
            compose.onNodeWithContentDescription("${template.goal}的更多操作").performClick()
            compose.onNodeWithText("查看执行过程").performClick()
            val app = org.robolectric.RuntimeEnvironment.getApplication()
            assertEquals("返回模板库", org.robolectric.Shadows.shadowOf(app).nextStartedActivity.getStringExtra("back"))
            compose.onNodeWithTag("library_section_1").performClick()
            compose.onNodeWithText(draft.goal).assertIsDisplayed()
            compose.onNodeWithText(template.goal).assertDoesNotExist()
        } finally { listOf(card, template, draft).forEach { rows.remove(it.id) } }
    }

    @Test
    fun readyTemplateUsesSameConfigurationFromLibraryAndSpace() {
        val events = mutableListOf<String>()
        val ready = com.life0423.carddock.cainiaoEmptyTemplate.copy(id = "personal-pickup", title = "我的取件模板")
        val personal = listOf(PersonalTemplate(ready.id, published = ready),
            PersonalTemplate("draft-only", edit = TemplateEdit("未核验草稿", "", null)))
        val state = mutableStateOf(UiFixture.state(listOf(com.life0423.carddock.cainiaoEmptyTemplate))
            .copy(personalTemplates = personal))
        val actions = UiFixture.actions(events).copy(
            openCreator = { template ->
                events += template?.id.orEmpty()
                state.value = state.value.copy(draft = CreationDraft(
                    title = template?.title.orEmpty(), prompt = template?.examplePrompt.orEmpty(), template = template))
            },
            closeCreator = { state.value = state.value.copy(draft = null) },
        )
        compose.setContent { CardDockApp(state.value, actions) }
        compose.onNodeWithTag("tab_templates").performClick()
        compose.onNodeWithText("未核验草稿").assertDoesNotExist()
        compose.onNodeWithTag("library_section_1").performClick()
        compose.onNodeWithText("未核验草稿").assertIsDisplayed()
        compose.onNodeWithTag("library_section_0").performClick()
        compose.onNodeWithTag("use_template_personal-pickup").performClick()
        compose.onNodeWithText("配置卡片").assertIsDisplayed()
        compose.onNodeWithTag("creator_close").performClick()
        compose.onNodeWithTag("tab_space").performClick()
        compose.onNodeWithTag("open_creator").performClick()
        compose.onNodeWithTag("pick_template_draft-only").assertDoesNotExist()
        compose.onNodeWithTag("pick_template_personal-pickup").performClick()
        compose.onNodeWithText("配置卡片").assertIsDisplayed()
        assertEquals(listOf(ready.id, ready.id), events)
    }

    /** 仅重排商品字段仍保留独立核验的展示价；裁剪字段后按用户的可见范围展示。 */
    @Test fun priceTicketSurvivesReorderingAllFields() {
        val template = jdTemplates.first()
        val fields = listOf(FieldValue("variant", "规格", "27 英寸"), FieldValue("title", "名称", "测试商品"))
        val card = UiFixture.card.copy(rule = template.rule, kind = TemplateKind.SHOPPING,
            snapshot = UiFixture.snapshot.copy(fields = fields),
            display = fields.reversed().map { com.life0423.carddock.core.model.DisplayField(it.key, it.label) },
            appPrice = com.life0423.carddock.core.model.AppPrice("21.50", "CNY", "27 英寸", "展示价", null, 1000))
        val state = mutableStateOf(UiFixture.state(emptyList()).copy(cards = listOf(card)))
        compose.setContent { CardDockApp(state.value, UiFixture.actions(mutableListOf())) }
        compose.onNodeWithText("¥21.50").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(cards = listOf(card.copy(display = card.display.take(1)))) }
        compose.onNodeWithText("¥21.50").assertDoesNotExist()
    }

    @Test
    fun shoppingIsInsideSpaceOnNativeBuild() {
        val events = mutableListOf<String>()
        val cards = jdTemplates.map { template ->
            UiFixture.card.copy(id = template.id, title = template.title, kind = TemplateKind.SHOPPING,
                rule = template.rule, snapshot = UiFixture.snapshot.copy(fields = listOf(
                    FieldValue("title", "商品名称", template.title),
                    FieldValue("variant", "规格", "27 英寸"),
                )))
        }
        compose.setContent {
            CardDockApp(
                UiFixture.state(TemplateCatalog.templates).copy(cards = cards),
                UiFixture.actions(events).copy(
                    toggleFavorite = { events += "favorite:$it" },
                    refreshSequence = { events += it.joinToString() },
                ),
                tools = PrototypeTools(),
            )
        }
        compose.onNodeWithTag("tab_shopping").assertDoesNotExist()
        compose.onNodeWithText("选购").assertDoesNotExist()
        compose.onNodeWithTag("tab_space").assertIsSelected()
        compose.onNodeWithText("卡片空间").assertIsDisplayed()
        compose.onNodeWithContentDescription("收藏京东商品 A").performClick()
        assertEquals(listOf("favorite:jd-a"), events)
        compose.onNodeWithTag("space_list").performScrollToNode(hasTestTag("open_shopping"))
        compose.onNodeWithTag("open_shopping").performClick()
        compose.onNodeWithText("京东商品对比").assertIsDisplayed()
        assertEquals(listOf("favorite:jd-a", "jd-a, jd-b"), events)
    }

    @Test
    fun creatorRequiresVerifiedPreviewAndSupportsRevalidationAfterTakeover() {
        val events = mutableListOf<String>()
        val express = TemplateCatalog.templates.first { it.id == "express-pickup" }
        val draft = CreationDraft(
            prompt = express.examplePrompt,
            title = "我的待取快递",
            template = express,
            phase = RunPhase.NEEDS_USER,
            message = "模拟页面需要确认后继续。",
        )
        val state = mutableStateOf(UiFixture.state(TemplateCatalog.templates).copy(draft = draft))
        compose.setContent { CardDockApp(state.value, UiFixture.actions(events)) }

        compose.onNodeWithTag("creator_save").assertDoesNotExist()
        compose.onNodeWithText("完成操作后重新试运行").performClick()
        assertEquals(listOf("trial"), events)

        compose.runOnIdle { state.value = state.value.copy(draft = draft.copy(phase = RunPhase.RUNNING)) }
        compose.onNodeWithTag("creator_prompt").assertDoesNotExist()
        compose.onNodeWithTag("creator_trial").assertIsNotEnabled()
        compose.onNodeWithTag("creator_save").assertDoesNotExist()

        compose.runOnIdle {
            state.value = state.value.copy(
                draft = draft.copy(
                    phase = RunPhase.SUCCEEDED,
                    preview = UiFixture.snapshot,
                    message = "试运行通过，已核验对象与必需字段。",
                ),
            )
        }
        compose.onNodeWithTag("creator_save").assertIsEnabled()
        compose.onNodeWithText("确认后，刷新会直接运行已核验的规则。").performScrollTo()
        compose.captureScreen("03-create-preview")
        compose.onNodeWithTag("creator_save").performClick()
        assertEquals(listOf("trial", "save"), events)
    }

    /** 断连后打开详情只查看快照；来源时间和最后一次成功核验分别有自己的标签。 */
    @Test
    fun disconnectedAmountDetailKeepsBothTimesWithoutRefreshing() {
        val events = mutableListOf<String>()
        val snapshot = UiFixture.snapshot.copy(fields = listOf(
            FieldValue("amount", "订单合计", "¥18.60"),
            FieldValue("orderTime", "下单时间", "2026-10-12 11:10:14"),
        ))
        val card = UiFixture.card.copy(snapshot = snapshot,
            run = RunInfo("lost-helper", RunPhase.NEEDS_USER, message = "助手未连接",
                missingCapability = com.life0423.carddock.core.model.PhoneCapability.HELPER))
        compose.setContent {
            CardDockApp(UiFixture.state(TemplateCatalog.templates).copy(cards = listOf(card)), UiFixture.actions(events))
        }
        compose.onNodeWithText("连接后台助手").assertExists()
        compose.onNodeWithTag("space_menu_express-card").performClick()
        compose.onNodeWithText("查看详情").performClick()
        for (text in listOf("¥18.60", "下单时间", "2026-10-12 11:10:14", "卡坞上次成功核验",
            formatTimestamp(snapshot.fetchedAt, includeYear = true), "暂显示上次有效结果", "助手未连接", "连接后台助手")) {
            compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        }
        assertTrue(events.isEmpty())
    }

    /** 自定义卡面若隐藏了价格口径字段，详情仍应保留一次来源。 */
    @Test fun priceSourceAppearsOnceWithOrWithoutSelectedBasis() {
        val basis = com.life0423.carddock.platform.PRICE_BASIS
        val fields = listOf(FieldValue("price", "官网标价", "¥11999"), FieldValue("basis", "价格来源", basis))
        val card = mutableStateOf(UiFixture.card.copy(snapshot = UiFixture.snapshot.copy(fields = fields, sourceLabel = basis)))
        compose.setContent { FunctionCard(card.value, {}) }
        compose.onNodeWithText(basis).assertExists()
        compose.runOnIdle {
            card.value = card.value.copy(display = listOf(com.life0423.carddock.core.model.DisplayField("price", "官网标价")))
        }
        compose.onNodeWithText("价格来源").assertDoesNotExist()
        compose.onNodeWithText(basis).assertExists()
    }

    /** 列表动作在票面和详情使用同一名称，点击票面仍走刷新。 */
    @Test fun pickupListCardKeepsRefreshAndDestination() {
        val events = mutableListOf<String>()
        val card = UiFixture.card.copy(opensPickupList = true)
        val code = com.life0423.carddock.cainiaoEmptyTemplate.copy(pickupEntry = com.life0423.carddock.core.model.PickupEntry.IDENTITY)
        compose.setContent {
            CardDockApp(UiFixture.state(TemplateCatalog.templates).copy(cards = listOf(card),
                personalTemplates = listOf(PersonalTemplate(code.id, published = code))), UiFixture.actions(events))
        }
        compose.onNodeWithText("出示取件码").assertExists()
        compose.onNodeWithText("打开菜鸟待取列表").assertExists()
        compose.onNodeWithTag("refresh_express-card").performClick()
        compose.onNodeWithTag("space_menu_express-card").performClick()
        compose.onNodeWithText("查看详情").performClick()
        compose.onNodeWithText("打开菜鸟待取列表").performScrollTo().assertIsDisplayed()
        assertEquals(listOf("refresh:express-card"), events)
    }

    @Test
    fun cardSupportsRefreshCancellationTakeoverAndConfirmedRemoval() {
        val events = mutableListOf<String>()
        val state = mutableStateOf(UiFixture.state(TemplateCatalog.templates).copy(cards = listOf(UiFixture.card)))
        compose.setContent { CardDockApp(state.value, UiFixture.actions(events)) }

        compose.onNodeWithTag("space_list").performScrollToNode(hasTestTag("refresh_express-card"))
        compose.onNodeWithTag("refresh_express-card").performClick()
        compose.onNodeWithTag("space_menu_express-card").performClick()
        compose.onNodeWithTag("open_card_express-card").performClick()

        compose.runOnIdle {
            state.value = state.value.copy(
                cards = listOf(UiFixture.card.copy(run = RunInfo("run", RunPhase.RUNNING))),
            )
        }
        compose.onNodeWithTag("cancel_express-card").performClick()

        compose.runOnIdle {
            state.value = state.value.copy(
                cards = listOf(UiFixture.card.copy(run = RunInfo("run", RunPhase.NEEDS_USER))),
            )
        }
        compose.onNodeWithTag("resume_express-card").performClick()
        compose.onNodeWithTag("menu_express-card").performClick()
        compose.onNodeWithTag("remove_express-card").performClick()
        compose.onNodeWithTag("confirm_remove_express-card").assertIsDisplayed().performClick()

        assertEquals(
            listOf("refresh:express-card", "cancel:express-card", "resume:express-card", "delete:express-card"),
            events,
        )
    }

    /** 放大字号后，长内容滚动时，底栏操作仍可直接点击。 */
    @Test fun largeFontBookCardKeepsRefreshVisibleAndLinkPaired() {
        val events = mutableListOf<String>()
        val search = com.life0423.carddock.BookSearch("悬疑小说", 5)
        val snapshot = UiFixture.snapshot.copy(fields = buildList {
            add(FieldValue("summary", "我的查书进展", "已核对 5/5 本"))
            for (i in 1..5) {
                add(FieldValue("book$i", "图书 $i", "书目 $i\n商品标题 $i\n推荐依据 · 精装版"))
                add(FieldValue("url$i", "商品链接 $i", "https://item.taobao.com/item.htm?id=12345678$i"))
            }
        })
        val card = UiFixture.card.copy(title = "淘宝找书", rule = search.template("").rule, snapshot = snapshot)
        val enlarged = mutableStateOf(false)
        val sample = card.copy(title = "淘宝找书 · 悬疑小说", snapshot = snapshot.copy(fields = listOf(
            FieldValue("summary", "核对进度", "已核对 2/2 本"),
            FieldValue("book1", "图书 1", "夏天烟火和我的尸体\n精装收藏版\n推荐依据 · 商品详情标注「精装收藏」、「日式恐怖」"),
            snapshot.fields[2],
            FieldValue("book2", "图书 2", "福尔摩斯探案全集\n精装硬壳版\n推荐依据 · 商品详情标注「无删减」、「精装硬壳」"),
            snapshot.fields[4],
        ), sourceLabel = "淘宝官方 App · 独立查询屏"))
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                androidx.compose.ui.unit.Density(density.density, if (enlarged.value) 1.5f else 1f)) {
                val selected = if (enlarged.value) card else sample
                CardDockApp(UiFixture.state(TemplateCatalog.templates).copy(cards = listOf(selected)), UiFixture.actions(events))
            }
        }
        compose.onNodeWithTag("space_menu_${card.id}").performClick()
        compose.onNodeWithTag("open_card_${card.id}").performClick()
        compose.onNodeWithTag("refresh_${card.id}").assertIsDisplayed()
        for (label in listOf("刷新卡片", "打开淘宝商品")) {
            val text = compose.onAllNodes(androidx.compose.ui.test.hasText(label), useUnmergedTree = true)[0]
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            text.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(16.sp, layouts.single().layoutInput.style.fontSize)
            assertEquals(androidx.compose.ui.text.font.FontWeight.Medium, layouts.single().layoutInput.style.fontWeight)
        }
        compose.onNodeWithTag("refresh_${card.id}").assertHeightIsAtLeast(48.dp)
        compose.captureScreen("21-book-detail")
        compose.runOnIdle { enlarged.value = true }
        compose.onNodeWithText("我的查书进展").assertIsDisplayed()
        val refresh = compose.onNodeWithTag("refresh_${card.id}")
        refresh.assertIsDisplayed()
        val before = refresh.fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription("打开淘宝商品，书目 5").performScrollTo().assertIsDisplayed()
        assertEquals(before, refresh.fetchSemanticsNode().boundsInRoot)
        refresh.performClick()
        assertEquals(listOf("refresh:${card.id}"), events)
    }
}
