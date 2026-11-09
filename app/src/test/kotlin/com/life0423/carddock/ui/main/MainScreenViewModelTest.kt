package com.life0423.carddock.ui.main

import com.life0423.carddock.data.DataRepository
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** uiState 的初值是 Loading，first() 读到的就是这个初值。成功态由界面测试看文案。 */
class MainScreenViewModelTest {
  @Test
  fun uiState_initiallyLoading() = runTest {
    val viewModel = MainScreenViewModel(FakeMyModelRepository())
    assertEquals(MainScreenUiState.Loading, viewModel.uiState.first())
  }
}

private class FakeMyModelRepository : DataRepository {
  override val data: Flow<List<String>> = flow { emit(listOf("Sample")) }
}
