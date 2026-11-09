package com.life0423.carddock

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.life0423.carddock.ui.main.MainScreen

/**
 * 应用内导航。
 *
 * 官方 Empty Activity 模板带了 Navigation 3，首版只有 [Main] 一个目的地。
 * 返回栈由模板的 [rememberNavBackStack] 持有，这里不另写路由表。
 */
@Composable
fun MainNavigation() {
  val backStack = rememberNavBackStack(Main)

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    entryProvider =
      entryProvider {
        entry<Main> {
          MainScreen(onItemClick = { navKey -> backStack.add(navKey) })
        }
      },
  )
}
