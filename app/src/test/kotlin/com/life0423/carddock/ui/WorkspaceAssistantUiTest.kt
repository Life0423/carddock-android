package com.life0423.carddock.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.life0423.carddock.ai.WorkspaceProposal
import com.life0423.carddock.core.model.CardSnapshot
import com.life0423.carddock.core.model.CompareGroup
import com.life0423.carddock.core.model.FieldValue
import com.life0423.carddock.core.testing.UiFixture
import com.life0423.carddock.ui.theme.CardDockTheme
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h800dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class WorkspaceAssistantUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun demandSurvivesLeavingAssistant() {
        RuntimeEnvironment.getApplication().getSharedPreferences("space-assistant", Context.MODE_PRIVATE)
            .edit().clear().commit()
        val show = mutableStateOf(true)
        compose.setContent {
            CardDockTheme {
                if (show.value) AssistantScreen(listOf(UiFixture.card), emptyList(), { _, _ -> }, {}, onOpenSettings = {})
            }
        }

        compose.onNodeWithTag("assistant_demand").performTextInput("找一下平板卡")
        compose.runOnIdle { show.value = false }
        compose.onNodeWithTag("assistant_demand").assertDoesNotExist()
        compose.runOnIdle { show.value = true }
        compose.onNodeWithTag("assistant_demand").assertTextContains("找一下平板卡")
    }

    @Test
    fun aSavedProposalRequiresConfirmationAndUndoUsesTheCommittedGroupId() {
        val cards = listOf(UiFixture.card.copy(id = "a", title = "小平板"), UiFixture.card.copy(id = "b", title = "大平板"))
        val proposal = WorkspaceProposal("平板备选", cards.map { it.id })
        val preferences = RuntimeEnvironment.getApplication().getSharedPreferences("space-assistant", Context.MODE_PRIVATE)
        preferences.edit().clear().putString("demand", "分组平板").putString("proposal", Json.encodeToString(proposal)).commit()
        val groups = mutableStateOf(emptyList<CompareGroup>())
        var request: WorkspaceProposal? = null
        var undoId: String? = null
        compose.setContent {
            CardDockTheme {
                AssistantScreen(cards, groups.value, { name, members -> request = WorkspaceProposal(name, members) }, {
                    undoId = it
                    groups.value = emptyList()
                }, onOpenSettings = {})
            }
        }

        compose.onNodeWithTag("assistant_screen").performScrollToNode(hasTestTag("assistant_confirm_group"))
        compose.onNodeWithTag("assistant_confirm_group").assertIsDisplayed()
        assertNull(request)
        compose.onNodeWithTag("assistant_confirm_group").performClick()
        assertEquals(proposal, request)
        assertEquals("分组平板", preferences.getString("demand", null))
        compose.runOnIdle { groups.value = listOf(CompareGroup("committed", proposal.name, proposal.cardIds)) }

        compose.onNodeWithTag("assistant_screen").performScrollToNode(hasTestTag("assistant_undo_group"))
        assertNull(preferences.getString("proposal", null))
        compose.onNodeWithTag("assistant_undo_group").performClick()
        assertEquals("committed", undoId)
    }

    @Test
    fun manualSelectionShowsVerifiedValuesAndMissingFields() {
        val card = UiFixture.card.copy(snapshot = CardSnapshot(
            fields = listOf(FieldValue("stock", "库存", "有货")), fetchedAt = 1L, sourceLabel = "小米商城",
        ))
        val other = UiFixture.card.copy(id = "b", title = "另一个型号", snapshot = null)
        compose.setContent { CardDockTheme { CompareWithAssistant(listOf(card, other)) } }

        compose.onNodeWithTag("compare_with_assistant").performClick()
        compose.onNodeWithText("库存").performClick()
        compose.onNodeWithText("有货").assertIsDisplayed()
        compose.onAllNodesWithText("未取得").assertCountEquals(1)
        compose.onNodeWithText("小米商城", substring = true).assertIsDisplayed()
    }
}
