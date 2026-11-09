package com.life0423.carddock.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import com.life0423.carddock.R
import com.life0423.carddock.data.DefaultDataRepository
import com.life0423.carddock.ui.theme.CardDockTheme

/**
 * 主屏。收集 [MainScreenViewModel.uiState]，按加载、成功、失败三态切换。
 *
 * @param onItemClick 模板留给后续目的地的点击回调。首版按钮不调用它。
 * @param modifier 外部布局修饰。
 * @param viewModel 默认用 [DefaultDataRepository] 创建。测试可以传入别的实现。
 */
@Composable
fun MainScreen(
  onItemClick: (NavKey) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: MainScreenViewModel = viewModel { MainScreenViewModel(DefaultDataRepository()) },
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  when (val state = uiState) {
    MainScreenUiState.Loading -> {
      Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
      ) {
        CircularProgressIndicator()
      }
    }
    is MainScreenUiState.Success -> {
      MainScreen(data = state.data, modifier = modifier)
    }
    is MainScreenUiState.Error -> {
      Text("Error loading data: ${state.throwable.message}")
    }
  }
}

/**
 * 成功态的主屏内容。
 *
 * @param data 要逐条显示的文本。
 * @param modifier 外部布局修饰。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainScreen(data: List<String>, modifier: Modifier = Modifier) {
  Scaffold(
    modifier = modifier,
    topBar = {
      CenterAlignedTopAppBar(
        title = { Text(stringResource(R.string.app_name)) },
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = MaterialTheme.colorScheme.primaryContainer,
          titleContentColor = MaterialTheme.colorScheme.primary
        )
      )
    }
  ) { innerPadding ->

    // App Content

    Column(
      modifier = Modifier.padding(innerPadding),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      data.forEach { Greeting(it) }
      Button(
        modifier = Modifier.padding(24.dp, 24.dp),
        onClick = { /* Button clicked */ }
      ) {
        Text("OK")
      }

      // TODO: Extend app UI with more Jetpack Compose UI elements here

    }
  }
}

/**
 * 一行占位问候。
 *
 * @param name 插进文案的名字。
 * @param modifier 外部布局修饰。
 */
@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
  Text(
    text = "Hello $name !",
    modifier = modifier.padding(16.dp, 16.dp),
    style = MaterialTheme.typography.bodyLarge
  )
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun MainScreenPreview() {
  CardDockTheme { MainScreen(listOf("Android")) }
}

