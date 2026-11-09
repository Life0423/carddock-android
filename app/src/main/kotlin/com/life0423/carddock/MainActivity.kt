package com.life0423.carddock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.life0423.carddock.ui.theme.CardDockTheme

/**
 * 应用启动入口。
 *
 * 只负责开窗口并把 [MainNavigation] 放进主题。卡片台面和无障碍采集不在这里实现。
 */
class MainActivity : ComponentActivity() {
  /**
   * 创建窗口并挂上 Compose。
   *
   * @param savedInstanceState 系统在配置变更后交回的状态。首版没有自己的状态要恢复。
   */
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    enableEdgeToEdge()
    setContent {
      CardDockTheme { Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { MainNavigation() } }
    }
  }
}
