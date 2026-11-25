package com.life0423.carddock.ui

import android.content.Intent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.activity.ComponentActivity
import com.life0423.carddock.core.model.PageTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** 用户打开并核对同一链接后才能提交；替换链接作废旧确认。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h800dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PageCardUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun pageSaveRequiresUserConfirmationAfterLaunch() {
        var saved: Pair<String, PageTarget>? = null
        compose.setContent {
            PageCreator("https://example.com/books", { name, page -> saved = name to page; true }, {})
        }
        compose.onNodeWithTag("page_title").performTextInput("我的书架")
        compose.onNodeWithTag("page_save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("page_trial").performScrollTo().performClick()
        val launched = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, launched.action)
        assertEquals("https://example.com/books", launched.dataString)
        compose.onNodeWithTag("page_save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("page_confirm").performScrollTo().performClick()
        compose.onNodeWithTag("page_save").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals("我的书架", saved?.first)
            assertEquals("https://example.com/books", saved?.second?.url)
        }
    }

    @Test
    fun sharedTextOnlyAcceptsAnHttpsDestination() {
        assertEquals("https://example.com/books", pageLink("分享一本书 https://example.com/books。")?.toASCIIString())
        assertNull(pageLink("intent://settings/#Intent;end"))
        assertNull(pageLink("https://example.com@other.com/books"))
    }
}
