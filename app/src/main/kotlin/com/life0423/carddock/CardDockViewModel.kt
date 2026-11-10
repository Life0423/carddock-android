package com.life0423.carddock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.life0423.carddock.core.session.PrototypeController

/**
 * Presentation binding only. Collecting state never schedules another device operation.
 * 默认工厂只能无参构造，所以由 [factory] 把应用级控制器传进来。旋转不会新开控制器。
 */
class CardDockViewModel(controller: PrototypeController) : ViewModel() {
    val state = controller.state
    val actions = controller.actions

    companion object {
        fun factory(controller: PrototypeController): ViewModelProvider.Factory = viewModelFactory {
            initializer { CardDockViewModel(controller) }
        }
    }
}
