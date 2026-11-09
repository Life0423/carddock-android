package com.life0423.carddock

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** 唯一的导航目的地。Navigation 3 要求目的地可序列化，所以保留模板的 [Serializable]。 */
@Serializable data object Main : NavKey
