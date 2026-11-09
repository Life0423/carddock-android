package com.life0423.carddock.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.life0423.carddock.R
import com.life0423.carddock.ui.theme.CardDockTheme

/**
 * 单页骨架。
 *
 * 卡片数据还没接入，所以这里不放仓库、ViewModel 或加载态，只显示产品名。
 *
 * @param modifier 外部布局修饰。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(modifier: Modifier = Modifier) {
  Scaffold(
    modifier = modifier,
    topBar = {
      CenterAlignedTopAppBar(
        title = { Text(stringResource(R.string.app_name)) },
        colors =
          TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            // 亮色 primary 叠在 primaryContainer 上大约只有 1.72:1，标题改用配套的前景色。
            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
          ),
      )
    },
  ) { innerPadding ->
    Box(Modifier.padding(innerPadding).fillMaxSize())
  }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun MainScreenPreview() {
  CardDockTheme { MainScreen() }
}
