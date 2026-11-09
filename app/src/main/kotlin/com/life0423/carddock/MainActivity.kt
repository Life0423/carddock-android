package com.life0423.carddock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.life0423.carddock.ui.CardDockApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val controller = (application as CardDockApplication).container.controller
        setContent {
            val viewModel: CardDockViewModel = viewModel(factory = CardDockViewModel.factory(controller))
            val state = viewModel.state.collectAsStateWithLifecycle()
            CardDockApp(state = state.value, actions = viewModel.actions)
        }
    }
}
