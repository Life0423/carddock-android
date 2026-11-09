package com.life0423.carddock.ui.main

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.life0423.carddock.ui.theme.CardDockTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 本地单测主屏静态占位。
 *
 * 直接组合真实的 [MainScreen]，确认标题是产品名。
 * 不再测已删除的 ViewModel 和加载态。
 *
 * 单测跑在 API 35：Robolectric 4.17 里 API 36 和 37 的沙箱要 Java 21，
 * 而这个模块的工具链是 JDK 17。界面没有 35 以上的专有调用。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainScreenTest {
  @get:Rule val composeTestRule = createComposeRule()

  /** 标题应显示 strings.xml 里的产品名。改名时这里要一起改。 */
  @Test
  fun appName_isShown() {
    composeTestRule.setContent {
      CardDockTheme { MainScreen() }
    }
    composeTestRule.onNodeWithText("卡坞").assertExists()
  }
}
