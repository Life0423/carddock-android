package com.life0423.carddock.ui

import androidx.compose.runtime.mutableStateOf
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
    @get:Rule
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
        compose.onNodeWithText("暂无已下载模板").assertIsDisplayed()
        compose.onNodeWithTag("library_section_2").performClick()
        compose.onNodeWithText("暂无收藏模板").assertIsDisplayed()
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
    fun pickerReturnsWithNewTemplateAfterPublishing() {
        val ready = com.life0423.carddock.cainiaoEmptyTemplate.copy(id = "created")
        val state = mutableStateOf(UiFixture.state(listOf(ready)))
        val actions = UiFixture.actions(mutableListOf()).copy(
            openTemplateEditor = {
                state.value = state.value.copy(draft = CreationDraft(
                    personalTemplateId = ready.id, template = ready, prompt = ready.examplePrompt,
                    title = ready.title, preview = UiFixture.snapshot, phase = RunPhase.SUCCEEDED,
                ))
            },
            saveDraft = {
                state.value = state.value.copy(draft = null, personalTemplates = listOf(PersonalTemplate(ready.id, ready)))
            },
        )
        compose.setContent { CardDockApp(state.value, actions) }
        compose.onNodeWithTag("open_creator").performClick()
        compose.onNodeWithTag("pick_template_created").assertDoesNotExist()
        compose.onNodeWithTag("picker_new_template").performClick()
        compose.onNodeWithTag("creator_save").performScrollTo().performClick()
        compose.onNodeWithTag("pick_template_created").assertIsDisplayed()
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
        compose.onNodeWithText("未核验草稿").assertIsDisplayed()
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

        compose.onNodeWithTag("creator_save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("完成操作后重新试运行").performScrollTo().performClick()
        assertEquals(listOf("trial"), events)

        compose.runOnIdle { state.value = state.value.copy(draft = draft.copy(phase = RunPhase.RUNNING)) }
        compose.onNodeWithTag("creator_prompt").assertDoesNotExist()
        compose.onNodeWithTag("creator_trial").assertIsNotEnabled()
        compose.onNodeWithTag("creator_save").assertIsNotEnabled()

        compose.runOnIdle {
            state.value = state.value.copy(
                draft = draft.copy(
                    phase = RunPhase.SUCCEEDED,
                    preview = UiFixture.snapshot,
                    message = "试运行通过，已核验对象与必需字段。",
                ),
            )
        }
        compose.onNodeWithTag("creator_save").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("确认后，刷新会直接运行已核验的规则。").performScrollTo()
        compose.captureScreen("03-create-preview")
        compose.onNodeWithTag("creator_save").performClick()
        assertEquals(listOf("trial", "save"), events)
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
}
