package com.life0423.carddock.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** UI tests for [com.life0423.carddock.ui.main.MainScreen]. */
class MainScreenTest {

  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  @Before
  fun setup() {
    composeTestRule.setContent { MainScreen(FAKE_DATA) }
  }

  @Test
  fun firstItem_exists() {
    // Greeting 的文案是 "Hello $name !"（感叹号前有空格），断言必须和界面对上。
    FAKE_DATA.forEach { composeTestRule.onNodeWithText("Hello $it !").assertExists() }
  }
}

private val FAKE_DATA = listOf("Sample1", "Sample2", "Sample3")
