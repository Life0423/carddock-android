package com.life0423.carddock.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.life0423.carddock.core.model.CompareGroup
import com.life0423.carddock.core.model.TemplateKind
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

/** 检查真实 Compose 路由与动作派发；领域提交结果由固定状态输入，不复制分组算法。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h800dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CompareGroupUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val base = UiFixture.state(TemplateCatalog.templates).let {
        it.copy(cards = it.cards + UiFixture.card.copy(id = "travel-card", title = "周末行程", kind = TemplateKind.TRAVEL))
    }
    private val group = CompareGroup("weekend", "今天怎么安排", base.cards.map { it.id })

    @Test
    fun groupEditorRequiresNameAndTwoCardsAndWaitsForSavedState() {
        val events = mutableListOf<String>()
        val state = mutableStateOf(base)
        compose.setContent { CardDockApp(state.value, UiFixture.actions(events)) }

        compose.onNodeWithTag("open_creator").performClick()
        compose.onNodeWithTag("create_group").performClick()
        compose.onNodeWithTag("save_group").assertIsNotEnabled()
        compose.onNodeWithTag("group_name").performTextInput("今日对比")
        compose.onNodeWithTag("select_express-card").performClick()
        compose.onNodeWithTag("save_group").assertIsNotEnabled()
        compose.onNodeWithTag("select_delivery-card").performClick()
        compose.onNodeWithTag("save_group").assertIsEnabled().performClick()
        assertEquals(listOf("group:create:今日对比:express-card, delivery-card"), events)
        // 点击并不代表提交成功：领域尚未回传新组时表单仍在，输入不会丢失。
        compose.onNodeWithTag("group_name").assertTextContains("今日对比")
        compose.runOnIdle {
            state.value = base.copy(groups = listOf(CompareGroup("saved", "今日对比", listOf("express-card", "delivery-card"))))
        }
        compose.onNodeWithTag("group_name").assertDoesNotExist()
        compose.onNodeWithTag("preview_saved").assertIsDisplayed()
        compose.onNodeWithTag("preview_express-card").assertDoesNotExist()
        compose.onNodeWithTag("preview_delivery-card").assertDoesNotExist()
        compose.onNodeWithTag("preview_travel-card").assertIsDisplayed()
        compose.captureScreen("07-comparison-feed")
    }

    @Test
    fun memberSwitchExpandReturnRemoveAndDissolveKeepValidRoutes() {
        val events = mutableListOf<String>()
        val state = mutableStateOf(base.copy(groups = listOf(group)))
        compose.setContent { CardDockApp(state.value, UiFixture.actions(events)) }

        compose.onNodeWithTag("preview_weekend").performClick()
        compose.onNodeWithTag("member_delivery-card").performClick()
        compose.onNodeWithTag("card_delivery-card").assertIsDisplayed()
        assertTrue("打开或切换成员不能自行刷新", events.isEmpty())
        compose.captureScreen("08-comparison-members")
        compose.onNodeWithTag("member_delivery-card").assertDoesNotExist()
        compose.onNodeWithTag("refresh_delivery-card").performScrollTo().performClick()
        compose.onNodeWithTag("preview_back").performClick()
        compose.onNodeWithTag("member_delivery-card").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("manage_group").performClick()

        compose.onNodeWithTag("ungroup_delivery-card").performScrollTo().performClick()
        assertEquals(listOf("refresh:delivery-card", "group:remove:weekend:delivery-card"), events)
        compose.runOnIdle {
            state.value = base.copy(
                cards = listOf(base.cards[0], base.cards[2], base.cards[1]),
                groups = listOf(group.copy(memberCardIds = listOf("express-card", "travel-card"))),
            )
        }
        compose.onNodeWithTag("ungroup_delivery-card").assertDoesNotExist()
        compose.onNodeWithTag("ungroup_express-card").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("dissolve_group").performScrollTo().performClick()
        compose.onNodeWithTag("confirm_dissolve").performClick()
        assertEquals("group:dissolve:weekend", events.last())
        compose.runOnIdle { state.value = state.value.copy(groups = emptyList()) }
        compose.onNodeWithTag("preview_back").assertDoesNotExist()
        compose.onNodeWithTag("preview_weekend").assertDoesNotExist()
        compose.onNodeWithTag("preview_express-card").assertExists()
        compose.onNodeWithTag("preview_delivery-card").assertExists()
        compose.onNodeWithTag("preview_travel-card").assertExists()
    }

    @Test
    fun memberSearchShowsOneGroupAndReturningPreservesFilters() {
        val events = mutableListOf<String>()
        compose.setContent { CardDockApp(base.copy(groups = listOf(group)), UiFixture.actions(events)) }

        compose.onNodeWithContentDescription("搜索卡片").performClick()
        compose.onNodeWithTag("space_search").performTextInput("午餐")
        compose.onNodeWithTag("preview_weekend").assertIsDisplayed()
        compose.onNodeWithTag("preview_delivery-card").assertDoesNotExist()
        compose.onNodeWithTag("preview_weekend").performClick()
        compose.onNodeWithTag("preview_back").performClick()
        compose.onNodeWithTag("space_search").assertTextContains("午餐")
        compose.onNodeWithTag("preview_weekend").assertIsDisplayed()
        assertTrue(events.isEmpty())
    }

    @Test
    fun returningToTheSameComparisonKeepsItsGoal() {
        compose.setContent { CardDockApp(base, UiFixture.actions(mutableListOf())) }
        compose.onNodeWithTag("open_creator").performClick()
        compose.onNodeWithTag("create_group").performClick()
        compose.onNodeWithTag("select_express-card").performClick()
        compose.onNodeWithTag("select_delivery-card").performClick()
        compose.onNodeWithTag("compare_selected").performClick()
        compose.onNodeWithTag("compare_goal").performTextInput("今天能用哪个")
        compose.onNodeWithText("返回选卡").performClick()
        compose.onNodeWithTag("compare_selected").performClick()
        compose.onNodeWithTag("compare_goal").assertTextContains("今天能用哪个")
    }
}
