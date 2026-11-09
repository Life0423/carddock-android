package com.life0423.carddock.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.life0423.carddock.data.DataRepository
import com.life0423.carddock.ui.main.MainScreenUiState.Success
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 主屏状态。
 *
 * 订阅开始时先发出 [MainScreenUiState.Loading]，仓库流出数据后变成 [MainScreenUiState.Success]。
 * [SharingStarted.WhileSubscribed] 的 5 秒是模板原值：短暂转屏不会重跑上游。
 *
 * @param dataRepository 文本来源。只在构造时读一次引用，内部不再校验。
 */
class MainScreenViewModel(dataRepository: DataRepository) : ViewModel() {
  val uiState: StateFlow<MainScreenUiState> =
    dataRepository.data
      .map<List<String>, MainScreenUiState>(::Success)
      .catch { emit(MainScreenUiState.Error(it)) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MainScreenUiState.Loading)
}

/** 主屏三态。失败时带上仓库抛出的异常，界面只显示它的 message。 */
sealed interface MainScreenUiState {
  object Loading : MainScreenUiState

  data class Error(val throwable: Throwable) : MainScreenUiState

  data class Success(val data: List<String>) : MainScreenUiState
}
