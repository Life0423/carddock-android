package com.life0423.carddock.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 界面数据入口。
 *
 * 首版只为模板屏幕提供示例文本，不读取其他应用，也不接无障碍。
 */
interface DataRepository {
  /** 主屏要显示的文本。每个收集者拿到一份列表。 */
  val data: Flow<List<String>>
}

/** 模板默认实现：发出一条固定示例，没有 IO。 */
class DefaultDataRepository : DataRepository {
  override val data: Flow<List<String>> = flow { emit(listOf("Android")) }
}
