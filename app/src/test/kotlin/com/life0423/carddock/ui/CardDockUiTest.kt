package com.life0423.carddock.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
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

    @Test
    fun nativeTemplateAndPermissionEntryAreReachable() {
        var settingsOpened = false
        val templates = TemplateCatalog.templates + com.life0423.carddock.cainiaoEmptyTemplate
        compose.setContent { CardDockApp(UiFixture.state(templates), UiFixture.actions(mutableListOf()), { settingsOpened = true }) }
        compose.onNodeWithTag("tab_templates").performClick()
        compose.onNodeWithTag("templates_list").performScrollToNode(hasTestTag("use_template_cainiao-empty"))
        compose.onNodeWithText("真机只读模板").assertIsDisplayed()
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
        compose.onNodeWithTag("open_creator").assertIsDisplayed()
        compose.captureScreen("01-space")
        compose.onNodeWithTag("open_creator").performClick()

        compose.onNodeWithTag("tab_templates").performClick().assertIsSelected()
        compose.onNodeWithText("待取快递").assertIsDisplayed()
        compose.onNodeWithText("外卖进度").assertIsDisplayed()
        compose.captureScreen("02-templates")
        val brand = compose.onNodeWithText("卡坞").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val heading = compose.onNodeWithText("从一个好用的模板开始").fetchSemanticsNode().boundsInRoot
        assertTrue("Template content must stay below the persistent brand bar", heading.top >= brand.bottom)
        compose.onNodeWithTag("templates_list").performScrollToNode(hasText("出行安排"))
        compose.onNodeWithText("出行安排").assertIsDisplayed()
        compose.onNodeWithTag("templates_list").performScrollToNode(hasTestTag("use_template_express-pickup"))
        compose.onNodeWithTag("use_template_express-pickup").performClick()

        compose.onNodeWithTag("tab_profile").performClick().assertIsSelected()
        compose.captureScreen("04-profile")
        compose.onNodeWithTag("profile_list")
            .performScrollToNode(hasTestTag("scenario_READ_FAILURE"))
        compose.onNodeWithTag("scenario_READ_FAILURE").performClick()
        compose.onNodeWithTag("profile_list").performScrollToNode(hasTestTag("reset_demo"))
        compose.onNodeWithTag("reset_demo").performClick()
        compose.onNodeWithTag("confirm_reset").assertIsDisplayed().performClick()

        assertEquals(listOf("open:blank", "open:express-pickup", "scenario:READ_FAILURE", "reset"), events)
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
        compose.onNodeWithTag("creator_prompt").assertIsNotEnabled()
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
        compose.onNodeWithText("保存后，刷新会直接运行已核验的规则。").performScrollTo()
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

        compose.runOnIdle {
            state.value = state.value.copy(
                cards = listOf(UiFixture.card.copy(run = RunInfo("run", RunPhase.RUNNING))),
            )
        }
        compose.onNodeWithTag("space_list").performScrollToNode(hasTestTag("cancel_express-card"))
        compose.onNodeWithTag("cancel_express-card").performClick()

        compose.runOnIdle {
            state.value = state.value.copy(
                cards = listOf(UiFixture.card.copy(run = RunInfo("run", RunPhase.NEEDS_USER))),
            )
        }
        compose.onNodeWithTag("space_list").performScrollToNode(hasTestTag("resume_express-card"))
        compose.onNodeWithTag("resume_express-card").performClick()
        compose.onNodeWithTag("space_list").performScrollToNode(hasTestTag("menu_express-card"))
        compose.onNodeWithTag("menu_express-card").performClick()
        compose.onNodeWithTag("remove_express-card").performClick()
        compose.onNodeWithTag("confirm_remove_express-card").assertIsDisplayed().performClick()

        assertEquals(
            listOf("refresh:express-card", "cancel:express-card", "resume:express-card", "delete:express-card"),
            events,
        )
    }
}
