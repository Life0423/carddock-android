package com.life0423.carddock.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.life0423.carddock.ui.theme.CardDockTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 仪器测试主屏静态占位。
 *
 * 直接组合真实的 [MainScreen]，确认标题是产品名。
 * 骨架已去掉导航、仓库和加载态，这里不覆盖那些路径。
 */
class MainScreenTest {
  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  /** 用和启动页相同的主题挂上主屏，不绕开界面去喂假数据。 */
  @Before
  fun setup() {
    composeTestRule.setContent {
      CardDockTheme { MainScreen() }
    }
  }

  /** 标题应显示 strings.xml 里的产品名。改名时这里要一起改。 */
  @Test
  fun appName_exists() {
    composeTestRule.onNodeWithText("卡坞").assertExists()
  }
}
