package com.life0423.carddock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.life0423.carddock.core.session.PrototypeController

/** Presentation binding only. Collecting state never schedules another device operation. */
class CardDockViewModel(controller: PrototypeController) : ViewModel() {
    val state = controller.state
    val actions = controller.actions

    companion object {
        fun factory(controller: PrototypeController): ViewModelProvider.Factory = viewModelFactory {
            initializer { CardDockViewModel(controller) }
        }
    }
}
