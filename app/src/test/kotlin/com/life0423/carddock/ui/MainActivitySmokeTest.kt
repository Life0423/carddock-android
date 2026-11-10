package com.life0423.carddock.ui

import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.life0423.carddock.CardDockApplication
import com.life0423.carddock.MainActivity
import com.life0423.carddock.core.model.RunPhase
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.time.Duration

/** Covers the real manifest Application, composition root, lifecycle and demo gateway. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h800dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivitySmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun realEntryLoadsCardsAndRefreshesThroughVerifiedEngine() {
        val application = compose.activity.application as CardDockApplication
        val controller = application.container.controller
        compose.waitUntil(timeoutMillis = 10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            !controller.state.value.loading
        }

        val id = "starter-express-pickup"
        compose.onNodeWithTag("space_list").performScrollToNode(hasTestTag("refresh_$id"))
        compose.onNodeWithTag("refresh_$id").assertIsDisplayed().performClick()

        try {
            compose.waitUntil(timeoutMillis = 10_000) {
                // Disk writes precede the gateway, so advance after its delay is registered too.
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                controller.state.value.cards.firstOrNull { it.id == id }?.run?.phase == RunPhase.SUCCEEDED
            }
        } catch (failure: Exception) {
            throw AssertionError(
                "Real refresh did not complete: ${controller.state.value.cards.firstOrNull { it.id == id }?.run}",
                failure,
            )
        }
        val card = controller.state.value.cards.first { it.id == id }
        assertNotNull(card.snapshot)
        compose.onNodeWithTag("space_list").performScrollToNode(hasText("A3-6281"))
        compose.onNodeWithText("A3-6281").assertIsDisplayed()
        compose.captureScreen("05-real-app-verified")
    }
}
